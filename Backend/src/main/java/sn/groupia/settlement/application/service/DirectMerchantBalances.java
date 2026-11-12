package sn.groupia.settlement.application.service;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.payment.api.*;
import sn.groupia.settlement.api.dto.DirectSettlement;
import sn.groupia.settlement.domain.repository.MerchantFinanceStore;
import sn.groupia.settlement.domain.service.MerchantPayoutProvider;

/**
 * Direct sales and campaigns share the same merchant-payout registry and destination. A receipt
 * never becomes spendable merely because its pay-in succeeded.
 */
@Service
@Transactional(readOnly = true)
public class DirectMerchantBalances {
    private final JdbcTemplate jdbc;
    private final MerchantFinanceStore store;
    private final PaymentOperations payments;
    private final CommercialRefunds refunds;
    private final MerchantPayoutProvider provider;
    private final boolean directPayoutsEnabled;

    public DirectMerchantBalances(
            JdbcTemplate jdbc,
            MerchantFinanceStore store,
            PaymentOperations payments,
            CommercialRefunds refunds,
            MerchantPayoutProvider provider,
            @org.springframework.beans.factory.annotation.Value(
                            "${groupia.settlement.direct-payouts.enabled:false}")
                    boolean directPayoutsEnabled) {
        this.jdbc = jdbc;
        this.store = store;
        this.payments = payments;
        this.refunds = refunds;
        this.provider = provider;
        this.directPayoutsEnabled = directPayoutsEnabled;
    }

    private record Receipt(
            UUID id,
            String number,
            String orderStatus,
            BigDecimal acceptedAmount,
            UUID requestId,
            UUID paymentId,
            BigDecimal principal,
            BigDecimal seller,
            BigDecimal platformFee,
            String currency,
            String providerName,
            String account) {}

    public List<DirectSettlement> calculate(UUID org) {
        var receipts =
                jdbc.query(
                        """
                select d.id,d.order_number,d.status,d.accepted_total_xof,
                       c.payment_request_id,c.payment_id,c.principal,c.seller_principal,c.platform_fee,
                       c.currency,c.provider,c.provider_account
                  from direct_orders d
                  join provider_collections c
                    on c.organization_id=d.organization_id
                   and c.target_type='DIRECT_ORDER' and c.target_id=d.id
                   and c.purpose='DIRECT_ORDER_INITIAL'
                 where d.organization_id=?
                 order by d.placed_at,d.id
                """,
                        (rs, row) ->
                                new Receipt(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("order_number"),
                                        rs.getString("status"),
                                        BigDecimal.valueOf(rs.getLong("accepted_total_xof")),
                                        rs.getObject("payment_request_id", UUID.class),
                                        rs.getObject("payment_id", UUID.class),
                                        rs.getBigDecimal("principal"),
                                        rs.getBigDecimal("seller_principal"),
                                        rs.getBigDecimal("platform_fee"),
                                        rs.getString("currency"),
                                        rs.getString("provider"),
                                        rs.getString("provider_account")),
                        org);

        var payouts = store.payouts(org);
        var result = new ArrayList<DirectSettlement>();
        for (var receipt : receipts) {
            var proof = payments.settlementEvidence(org, receipt.requestId());
            var state = payments.requestState(org, receipt.requestId());
            var request = state.request();
            if (request == null
                    || !Objects.equals(request.id(), receipt.requestId())
                    || !Objects.equals(request.organizationId(), org)
                    || !Objects.equals(request.target(), PaymentTarget.directOrder(receipt.id()))
                    || request.purpose() != PaymentPurpose.DIRECT_ORDER_INITIAL
                    || request.acceptanceStatus() != AcceptanceStatus.CLOSED
                    || !Objects.equals(request.settledPaymentId(), receipt.paymentId())
                    || !Objects.equals(request.currency(), "XOF")
                    || request.expectedAmount().compareTo(receipt.principal()) != 0
                    || !Objects.equals(proof.organizationId(), org)
                    || !Objects.equals(proof.paymentRequestId(), receipt.requestId())
                    || !Objects.equals(proof.paymentId(), receipt.paymentId())
                    || !Objects.equals(proof.target(), PaymentTarget.directOrder(receipt.id()))
                    || proof.purpose() != PaymentPurpose.DIRECT_ORDER_INITIAL
                    || proof.acceptanceStatus() != AcceptanceStatus.CLOSED
                    || proof.status() != PaymentStatus.SUCCEEDED
                    || !Objects.equals(proof.provider(), receipt.providerName())
                    || !Objects.equals(proof.providerAccountReference(), receipt.account())
                    || !Objects.equals(proof.currency(), receipt.currency())
                    || !Objects.equals(proof.currency(), "XOF")
                    || proof.amount() == null
                    || proof.amount().compareTo(receipt.principal()) != 0
                    || receipt.acceptedAmount().compareTo(receipt.principal()) != 0
                    || receipt.seller().signum() < 0
                    || receipt.seller().add(receipt.platformFee()).compareTo(receipt.principal())
                            != 0
                    || proof.providerTransactionId() == null
                    || proof.providerTransactionId().isBlank()
                    || proof.confirmedAt() == null) {
                throw new IllegalStateException("Direct merchant collection source mismatch");
            }

            var refund = refunds.find(org, PaymentTarget.directOrder(receipt.id()));
            boolean externallyReimbursed =
                    "REFUNDED".equals(receipt.orderStatus())
                            && refund != null
                            && "EXTERNALLY_RECORDED".equals(refund.status())
                            && refund.legacyPlatformFee().signum() == 0
                            // Partial provider-funded refunds require a separate allocation
                            // model; never reimburse the full seller principal in that case.
                            && refund.previouslyRefunded().signum() == 0
                            && refund.amountDue().compareTo(receipt.principal()) == 0;
            boolean completed = "COMPLETED".equals(receipt.orderStatus()) && refund == null;
            boolean usable =
                    directPayoutsEnabled
                            && (completed || externallyReimbursed)
                            && !proof.reversal()
                            && state.operationalStatus() == PaymentOperationalStatus.SETTLED
                            && !state.reconciliationRequired()
                            && !state.attemptInProgress()
                            && Objects.equals(receipt.providerName(), provider.fundingProvider())
                            && Objects.equals(receipt.account(), provider.fundingAccount())
                            && (!"SASPAY".equals(receipt.providerName()) || provider.enabled());

            BigDecimal paid = BigDecimal.ZERO;
            BigDecimal reserved = BigDecimal.ZERO;
            for (var payout : payouts) {
                if (!Objects.equals(payout.directOrderId(), receipt.id())) continue;
                // Pay-in funding identity and payout adapter identity are distinct:
                // fundingProvider()/fundingAccount() identify the origin of funds,
                // whereas payout.provider()/providerAccountReference() identify the
                // external transfer operator. The funding-source checks above must
                // still pass before a new payout can be reserved.
                //
                // All durable debits/reservations for this exact organization-scoped
                // direct order count regardless of which payout adapter sent them.
                // Ignoring historical payouts after an adapter change would allow
                // withdrawing the same seller principal more than once.
                if (!Objects.equals(payout.currency(), receipt.currency())) {
                    throw new IllegalStateException("Direct payout currency mismatch");
                }
                if (payout.succeeded()) paid = paid.add(payout.amount());
                else if (payout.reserved()) reserved = reserved.add(payout.amount());
            }

            BigDecimal eligible = usable ? receipt.seller() : BigDecimal.ZERO;
            var available = eligible.subtract(paid).subtract(reserved).max(BigDecimal.ZERO);
            result.add(
                    new DirectSettlement(
                            receipt.id(),
                            receipt.number(),
                            receipt.orderStatus(),
                            receipt.currency(),
                            receipt.providerName(),
                            ProviderCollectionLedger.accountReference(receipt.account()),
                            receipt.seller(),
                            receipt.platformFee(),
                            eligible.subtract(paid),
                            paid,
                            reserved,
                            available,
                            paid.subtract(eligible).max(BigDecimal.ZERO)));
        }
        return List.copyOf(result);
    }

    /** Serializes payout reservation with direct-order state changes. Must run in a write tx. */
    public void lock(UUID org, UUID orderId) {
        var ids =
                jdbc.query(
                        "select id from direct_orders where organization_id=? and id=? for update",
                        (rs, row) -> rs.getObject("id", UUID.class),
                        org,
                        orderId);
        if (ids.size() != 1)
            throw new sn.groupia.shared.api.ApiException(
                    404, "DIRECT_ORDER_NOT_FOUND", "Commande directe introuvable.");
    }
}
