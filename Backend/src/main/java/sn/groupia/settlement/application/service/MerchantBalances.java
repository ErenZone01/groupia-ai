package sn.groupia.settlement.application.service;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.grouping.api.MerchantCampaignFacts;
import sn.groupia.ordering.api.MerchantOrderFacts;
import sn.groupia.settlement.api.dto.*;
import sn.groupia.settlement.domain.model.*;
import sn.groupia.settlement.domain.repository.MerchantFinanceStore;

@Service
@Transactional(readOnly = true)
public class MerchantBalances {
    private final MerchantFinanceStore store;
    private final sn.groupia.payment.api.CommercialRefunds refunds;
    private final sn.groupia.settlement.domain.service.MerchantPayoutProvider payoutProvider;
    private final MerchantCampaignFacts campaigns;
    private final MerchantOrderFacts orders;
    private final sn.groupia.payment.api.PaymentOperations payments;

    MerchantBalances(
            MerchantFinanceStore s,
            MerchantCampaignFacts c,
            MerchantOrderFacts o,
            sn.groupia.payment.api.PaymentOperations payments,
            sn.groupia.settlement.domain.service.MerchantPayoutProvider payoutProvider,
            sn.groupia.payment.api.CommercialRefunds refunds) {
        this.refunds = refunds;
        this.payoutProvider = payoutProvider;
        store = s;
        campaigns = c;
        orders = o;
        this.payments = payments;
    }

    /**
     * Caller authorizes the organization. Shared by reads and the locked reservation transaction.
     */
    public MerchantBalance calculate(
            UUID org, UUID includeCampaign, boolean manage, boolean destination, boolean provider) {
        var entries = store.ledger(org);
        var payouts = store.payouts(org);
        var ids = new TreeSet<UUID>();
        entries.forEach(e -> ids.add(e.campaignId()));
        payouts.forEach(p -> ids.add(p.campaignId()));
        if (includeCampaign != null) ids.add(includeCampaign);
        var rows = new ArrayList<CampaignSettlement>();
        for (var id : ids) {
            var campaign = campaigns.read(org, id);
            BigDecimal gross = BigDecimal.ZERO,
                    fees = BigDecimal.ZERO,
                    refund = BigDecimal.ZERO,
                    paid = BigDecimal.ZERO,
                    eligible = BigDecimal.ZERO,
                    reserved = BigDecimal.ZERO;
            for (var e : entries)
                if (e.campaignId().equals(id)) {
                    if (!e.currency().equals("XOF"))
                        throw new IllegalStateException("Merchant currency mismatch");
                    if (e.direction().equals("CREDIT")) {
                        gross = gross.add(e.amount());
                        fees = fees.add(e.platformFeeAmount());
                        // A payout account can only spend collections attributed to that exact
                        // source.
                        var source = payments.settlementEvidence(org, e.sourceId());
                        var sourceState = payments.requestState(org, e.sourceId());
                        var cancellation =
                                refunds.find(
                                        org,
                                        sn.groupia.payment.api.PaymentTarget.order(e.orderId()));
                        // A seller who returned the customer principal outside Groupia may recover
                        // the original retained funds. Historical platform-fee compensation needs
                        // a separate resolution; never silently reimburse less than the
                        // declaration.
                        boolean reimbursable =
                                cancellation != null
                                        && cancellation.status().equals("EXTERNALLY_RECORDED")
                                        && cancellation.legacyPlatformFee().signum() == 0;
                        if (((campaign.available() && orders.read(org, e.orderId()).accepted())
                                        || reimbursable)
                                && !source.reversal()
                                && sourceState.operationalStatus()
                                        == sn.groupia.payment.api.PaymentOperationalStatus.SETTLED
                                && !sourceState.reconciliationRequired()
                                && !sourceState.attemptInProgress()
                                && !"SASPAY".equals(source.provider())
                                && Objects.equals(
                                        source.provider(), payoutProvider.fundingProvider())
                                && Objects.equals(
                                        source.providerAccountReference(),
                                        payoutProvider.fundingAccount()))
                            eligible = eligible.add(e.amount());
                    } else {
                        if (e.type().equals("MERCHANT_PAYOUT")) paid = paid.add(e.amount());
                        else refund = refund.add(e.amount());
                        eligible = eligible.subtract(e.amount());
                    }
                }
            for (var p : payouts)
                if (p.campaignId().equals(id) && p.reserved()) reserved = reserved.add(p.amount());
            var balance = gross.subtract(refund).subtract(paid);
            rows.add(
                    new CampaignSettlement(
                            id,
                            campaign.title(),
                            campaign.status(),
                            "XOF",
                            gross,
                            fees,
                            refund,
                            paid,
                            balance,
                            eligible,
                            reserved,
                            eligible.subtract(reserved).max(BigDecimal.ZERO),
                            balance.negate().max(BigDecimal.ZERO)));
        }
        BigDecimal gross = BigDecimal.ZERO,
                fees = BigDecimal.ZERO,
                refund = BigDecimal.ZERO,
                paid = BigDecimal.ZERO,
                eligible = BigDecimal.ZERO,
                reserved = BigDecimal.ZERO;
        for (var r : rows) {
            gross = gross.add(r.grossMerchantCollections());
            fees = fees.add(r.platformFees());
            refund = refund.add(r.platformFundedRefunds());
            paid = paid.add(r.merchantPayouts());
            eligible = eligible.add(r.eligibleBalance());
            reserved = reserved.add(r.reservedForPayout());
        }
        var available = eligible.subtract(reserved).max(BigDecimal.ZERO);
        var limited =
                rows.stream()
                        .map(
                                r ->
                                        new CampaignSettlement(
                                                r.campaignId(),
                                                r.title(),
                                                r.status(),
                                                r.currency(),
                                                r.grossMerchantCollections(),
                                                r.platformFees(),
                                                r.platformFundedRefunds(),
                                                r.merchantPayouts(),
                                                r.collectedBalance(),
                                                r.eligibleBalance(),
                                                r.reservedForPayout(),
                                                r.availableForPayout().min(available),
                                                r.deficit()))
                        .toList();
        var balance = gross.subtract(refund).subtract(paid);
        return new MerchantBalance(
                "XOF",
                gross,
                fees,
                refund,
                paid,
                balance,
                eligible,
                reserved,
                available,
                balance.negate().max(BigDecimal.ZERO),
                manage,
                destination,
                provider,
                limited);
    }
}
