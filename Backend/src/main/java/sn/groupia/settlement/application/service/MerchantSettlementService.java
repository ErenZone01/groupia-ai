package sn.groupia.settlement.application.service;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.organization.api.*;
import sn.groupia.settlement.api.dto.*;
import sn.groupia.settlement.domain.model.*;
import sn.groupia.settlement.domain.repository.MerchantFinanceStore;
import sn.groupia.settlement.domain.service.MerchantPayoutProvider;

@Service
public class MerchantSettlementService {
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
        return store.payouts(org).stream().map(MerchantSettlementService::view).toList();
    }

    @Transactional(propagation = Propagation.NEVER)
    public MerchantPayoutView request(
            UUID org, UUID campaign, UUID key, UUID destination, boolean confirmed) {
        var p = transactions.reserve(org, campaign, key, destination, confirmed);
        if (transactions.claim(org, p.id())) {
            try {
                transactions.initiated(org, p.id(), provider.initiate(p));
            } catch (RuntimeException ex) {
                transactions.uncertain(org, p.id());
            }
        }
        return view(transactions.get(org, p.id()));
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

    private static MerchantPayoutView view(MerchantPayout p) {
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
                p.reconciliationReason());
    }
}
