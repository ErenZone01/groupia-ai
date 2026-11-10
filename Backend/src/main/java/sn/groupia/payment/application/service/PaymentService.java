package sn.groupia.payment.application.service;

import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.domain.service.*;

@Service
public class PaymentService implements PaymentOperations {
    private final PaymentTransactions transactions;
    private final ProviderRegistry providers;

    PaymentService(PaymentTransactions transactions, ProviderRegistry providers) {
        this.transactions = transactions;
        this.providers = providers;
    }

    @Override
    public PaymentAttemptRecoveryView latestAttempt(UUID org, UUID request) {
        return transactions.latestAttempt(org, request);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentRequestView createRequest(CreatePaymentRequest command) {
        return transactions.create(command);
    }

    @Override
    public PaymentRequestView initialRequest(UUID org, UUID order) {
        return transactions.initial(org, order);
    }

    @Override
    public PaymentRequestState requestState(UUID org, UUID id) {
        return transactions.state(org, id);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentRequestState lockRequestState(UUID org, UUID id) {
        return transactions.lockState(org, id);
    }

    @Override
    public PaymentSettlementEvidence settlementEvidence(UUID org, UUID id) {
        return transactions.evidence(org, id);
    }

    @Override
    public boolean providerAvailable(String name) {
        return providers.available(name);
    }

    @Override
    public PaymentRequestView request(UUID org, UUID id) {
        return transactions.request(org, id);
    }

    @Override
    public List<PaymentObligationView> shippingRequests(UUID org, UUID order) {
        return transactions.shippingRequests(org, order);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PaymentObligationView> lockShippingRequests(UUID org, UUID order) {
        return transactions.lockShippingRequests(org, order);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelShippingRequest(UUID org, UUID id) {
        transactions.cancelShippingRequest(org, id);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelDirectUnpaidRequest(UUID org, UUID requestId, UUID directOrderId) {
        transactions.cancelDirectUnpaidRequest(org, requestId, directOrderId);
    }

    @Override
    public boolean cancelInitialAcceptance(UUID org, UUID order) {
        return transactions.cancel(org, order);
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public PaymentAttemptView initiateInitial(UUID org, UUID order, String provider, String key) {
        return initiateInitial(org, order, provider, key, null);
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public PaymentAttemptView initiateInitial(
            UUID org, UUID order, String provider, String key, PaymentCheckoutContext context) {
        return initiateRequest(org, transactions.initial(org, order).id(), provider, key, context);
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public PaymentAttemptView initiateRequest(
            UUID org, UUID requestId, String provider, String key, PaymentCheckoutContext context) {
        return initiateRequestInternal(org, requestId, provider, key, context, null);
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public PaymentAttemptView initiateDirect(
            UUID org,
            UUID requestId,
            String key,
            String recoveryTokenHash,
            PaymentCheckoutContext context) {
        return initiateRequestInternal(
                org,
                requestId,
                context != null && context.feeQuoteId() != null ? "SASPAY" : "BICTORYS",
                key,
                context,
                recoveryTokenHash);
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public PaymentAttemptView initiateDirect(
            UUID org, UUID requestId, String provider, String key,
            String recoveryTokenHash, PaymentCheckoutContext context) {
        if (!"SASPAY".equals(provider) && !"BICTORYS".equals(provider)) {
            throw new sn.groupia.shared.api.ApiException(
                    409, "PAYMENT_PROVIDER_NOT_ALLOWED", "Prestataire non autorisé.");
        }
        return initiateRequestInternal(org, requestId, provider, key, context, recoveryTokenHash);
    }

    private PaymentAttemptView initiateRequestInternal(
            UUID org,
            UUID requestId,
            String provider,
            String key,
            PaymentCheckoutContext context,
            String recoveryTokenHash) {
        var adapter = providers.require(provider);
        if (!adapter.canQuoteForOrganization(org)) {
            throw new sn.groupia.shared.api.ApiException(
                    409, "SASPAY_PILOT_ORGANIZATION_NOT_ALLOWED",
                    "Le paiement n'est pas disponible pour cette boutique.");
        }
        var claim =
                transactions.claim(
                        org,
                        requestId,
                        adapter.name(),
                        adapter.accountReference(),
                        key,
                        context,
                        recoveryTokenHash);
        if (!claim.owned()) {
            return claim.receipt();
        }
        ProviderObservation result;
        try {
            result = adapter.initiate(claim.instruction());
        } catch (RuntimeException ex) {
            // No automatic POST retry: SasPay may have created a real payment.
            // Only log a code owned by our adapter, not an exception body/PII.
            String code =
                    "SASPAY".equals(adapter.name())
                                    && ex.getMessage() != null
                                    && ex.getMessage().matches("SASPAY_[A-Z0-9_]{1,100}")
                            ? ex.getMessage()
                            : "PROVIDER_INITIATION_UNCERTAIN";
            LoggerFactory.getLogger(PaymentService.class)
                    .warn(
                            "Payment initiation requires reconciliation provider={} reason={}",
                            adapter.name(),
                            code);
            return transactions.uncertain(claim.instruction().paymentId());
        }
        if (result != null && result.status() == PaymentStatus.SUCCEEDED) {
            ProviderObservation proof = null;
            try {
                proof =
                        adapter.verify(
                                claim.instruction()
                                        .withTransactionId(result.providerTransactionId()));
            } catch (RuntimeException ignored) {
                /* Durable claim remains available for reconciliation. */
            }
            return transactions.verifiedInitiationResult(
                    claim.instruction().paymentId(), result, proof);
        }
        return transactions.initiationResult(claim.instruction().paymentId(), result);
    }

    @Transactional(propagation = Propagation.NEVER)
    public PaymentAttemptView reconcile(UUID paymentId) {
        var instruction = transactions.reconciliationInstruction(paymentId);
        var adapter = providers.require(instruction.provider());
        ProviderObservation result;
        try {
            result = adapter.verify(instruction);
        } catch (RuntimeException ex) {
            return transactions.uncertain(paymentId);
        }
        // A hosted session can be PENDING without any financial transaction ID.
        // Do not mark it uncertain or invent an ID while the client is paying.
        if ("SASPAY".equals(instruction.provider())
                && result != null
                && result.status() == PaymentStatus.PENDING
                && result.providerTransactionId() == null
                && result.metadata() != null
                && result.metadata().chargeId() != null)
            return transactions.initiationResult(paymentId, result);
        return transactions.result(paymentId, result);
    }

    public List<UUID> reconciliationQueue(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Limit must be 1..100");
        }
        return transactions.uncertainPayments(limit);
    }
}
