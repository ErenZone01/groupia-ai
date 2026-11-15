package sn.groupia.settlement.application.service;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.organization.api.*;
import sn.groupia.settlement.api.dto.*;
import sn.groupia.settlement.domain.model.*;
import sn.groupia.settlement.domain.repository.MerchantFinanceStore;
import sn.groupia.settlement.domain.service.MerchantPayoutProvider;

@Service
public class MerchantSettlementService {
    private static final Logger LOG = LoggerFactory.getLogger(MerchantSettlementService.class);

    // Keep provider response bodies, numbers, credentials, and exception messages out of logs.
    // Only provider-owned static error codes can be logged.
    private static String safePayoutErrorCode(RuntimeException exception) {
        String candidate = exception.getMessage();
        return exception instanceof IllegalStateException
                        && candidate != null
                        && candidate.matches("SASPAY_PAYOUT_[A-Z0-9_]{3,80}")
                ? candidate
                : "UNCLASSIFIED";
    }

    private final MerchantPayoutTransactions transactions;
    private final MerchantFinanceStore store;
    private final MerchantBalances balances;
    private final MerchantPayoutProvider provider;
    private final OrganizationAccess access;

    MerchantSettlementService(
            MerchantPayoutTransactions t,
            MerchantFinanceStore s,
            MerchantBalances b,
            MerchantPayoutProvider p,
            OrganizationAccess a) {
        transactions = t;
        store = s;
        balances = b;
        provider = p;
        access = a;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MerchantBalance balance(UUID org, UUID campaign) {
        access.requirePermission(org, OrganizationPermission.VIEW_PAYMENTS);
        return balances.calculate(
                org,
                campaign,
                access.hasPermission(org, OrganizationPermission.EXECUTE_PAYOUTS),
                access.hasPermission(org, OrganizationPermission.MANAGE_PAYOUT_DESTINATION),
                transactions.available());
    }

    @Transactional(readOnly = true)
    public PayoutDestinationView destination(UUID org) {
        access.requirePermission(org, OrganizationPermission.VIEW_PAYMENTS);
        return store.destination(org).map(MerchantSettlementService::view).orElse(null);
    }

    public PayoutDestinationView destination(
            UUID org,
            UUID previous,
            String method,
            String phone,
            String holder,
            boolean confirmed) {
        return view(transactions.destination(org, previous, method, phone, holder, confirmed));
    }

    @Transactional(readOnly = true)
    public List<MerchantPayoutView> payouts(UUID org) {
        access.requirePermission(org, OrganizationPermission.VIEW_PAYMENTS);
        return store.payouts(org).stream().map(this::view).toList();
    }

    public PayoutFeeQuoteView quoteCampaign(UUID org, UUID campaign, UUID destination) {
        return quoteCampaign(org, campaign, destination, null);
    }

    public PayoutFeeQuoteView quoteCampaign(
            UUID org, UUID campaign, UUID destination, java.math.BigDecimal requestedAmount) {
        return PayoutFeeQuoteView.from(
                transactions.quoteCampaign(org, campaign, destination, requestedAmount));
    }

    public PayoutFeeQuoteView quoteDirect(UUID org, UUID directOrder, UUID destination) {
        return quoteDirect(org, directOrder, destination, null);
    }

    public PayoutFeeQuoteView quoteDirect(
            UUID org, UUID directOrder, UUID destination, java.math.BigDecimal requestedAmount) {
        return PayoutFeeQuoteView.from(
                transactions.quoteDirect(org, directOrder, destination, requestedAmount));
    }

    @Transactional(propagation = Propagation.NEVER)
    public MerchantPayoutView request(
            UUID org, UUID campaign, UUID key, UUID destination, boolean confirmed) {
        return request(org, campaign, key, destination, confirmed, null);
    }

    @Transactional(propagation = Propagation.NEVER)
    public MerchantPayoutView request(
            UUID org,
            UUID campaign,
            UUID key,
            UUID destination,
            boolean confirmed,
            UUID feeQuoteId) {
        return dispatch(
                transactions.reserve(org, campaign, key, destination, confirmed, feeQuoteId));
    }

    @Transactional(propagation = Propagation.NEVER)
    public MerchantPayoutView requestDirect(
            UUID org, UUID directOrderId, UUID key, UUID destination, boolean confirmed) {
        return requestDirect(org, directOrderId, key, destination, confirmed, null);
    }

    @Transactional(propagation = Propagation.NEVER)
    public MerchantPayoutView requestDirect(
            UUID org,
            UUID directOrderId,
            UUID key,
            UUID destination,
            boolean confirmed,
            UUID feeQuoteId) {
        return dispatch(
                transactions.reserveDirect(
                        org, directOrderId, key, destination, confirmed, feeQuoteId));
    }

    /**
     * A one-time, explicitly confirmed operator action against an EXISTING reserved payout. The
     * same provider key, body, and metadata are reused; no payout row is created.
     */
    @Transactional(propagation = Propagation.NEVER)
    public MerchantPayoutView recoverPilot(UUID org, UUID id, boolean confirmed) {
        MerchantPayout original = transactions.claimRecovery(org, id, confirmed);
        try {
            transactions.initiated(org, id, provider.initiate(original));
        } catch (RuntimeException ex) {
            LOG.warn(
                    "Merchant payout recovery uncertain payoutId={} provider={} errorCode={} exceptionType={}",
                    id,
                    original.provider(),
                    safePayoutErrorCode(ex),
                    ex.getClass().getSimpleName());
            transactions.recoveryUncertain(org, id);
        }
        return view(transactions.get(org, id));
    }

    private MerchantPayoutView dispatch(MerchantPayout p) {
        if (transactions.claim(p.organizationId(), p.id())) {
            try {
                transactions.initiated(p.organizationId(), p.id(), provider.initiate(p));
            } catch (RuntimeException ex) {
                // This exception is intentionally not propagated: outcome may be uncertain.
                // The reference and a safe code let operators diagnose the first failure
                // without leaking HTTP payloads or triggering a second payout request.
                LOG.warn(
                        "Merchant payout initiation uncertain payoutId={} provider={} errorCode={} exceptionType={}",
                        p.id(),
                        p.provider(),
                        safePayoutErrorCode(ex),
                        ex.getClass().getSimpleName());
                transactions.uncertain(p.organizationId(), p.id());
            }
        }
        return view(transactions.get(p.organizationId(), p.id()));
    }

    @Transactional(propagation = Propagation.NEVER)
    public MerchantPayoutView reconcile(UUID org, UUID id) {
        access.requirePermission(org, OrganizationPermission.EXECUTE_PAYOUTS);
        var p = transactions.get(org, id);
        io.micrometer.core.instrument.Metrics.counter(
                        "merchant_payout_reconciliation",
                        "provider",
                        p.provider(),
                        "method",
                        p.method())
                .increment();
        if (!p.succeeded()) {
            if (!transactions.available())
                throw new sn.groupia.shared.api.ApiException(
                        503,
                        "MERCHANT_PAYOUT_DISABLED",
                        "La vérification provider est indisponible.");
            if (p.providerTransactionId() == null) {
                transactions.requiresOperator(org, id);
                return view(transactions.get(org, id));
            }
            try {
                var proof = provider.verify(p);
                if (proof != null || !provider.publishesEvidence())
                    transactions.verified(org, id, proof);
            } catch (RuntimeException ex) {
                LOG.warn(
                        "Merchant payout reconciliation uncertain payoutId={} provider={} errorCode={} exceptionType={}",
                        id,
                        p.provider(),
                        safePayoutErrorCode(ex),
                        ex.getClass().getSimpleName());
                transactions.uncertain(org, id);
            }
        }
        return view(transactions.get(org, id));
    }

    private static PayoutDestinationView view(MerchantPayoutDestination d) {
        return new PayoutDestinationView(
                d.id(),
                d.method(),
                d.holderName(),
                d.maskedPhone(),
                d.status(),
                d.version(),
                d.createdAt());
    }

    private MerchantPayoutView view(MerchantPayout p) {
        var sellerQuote = store.findPayoutFeeQuote(p.id());
        return new MerchantPayoutView(
                p.id(),
                p.campaignId(),
                p.amount(),
                p.currency(),
                p.status(),
                p.method(),
                p.maskedPhone(),
                p.holderName(),
                p.requestedAt(),
                p.succeededAt(),
                p.reconciliationRequired(),
                p.reconciliationReason(),
                p.directOrderId(),
                sellerQuote
                        .map(sn.groupia.settlement.domain.model.MerchantPayoutFeeQuote::providerFee)
                        .orElse(null),
                sellerQuote
                        .map(sn.groupia.settlement.domain.model.MerchantPayoutFeeQuote::netAmount)
                        .orElse(null),
                sellerQuote.isPresent() ? "DEDUCTED" : null);
    }
}
