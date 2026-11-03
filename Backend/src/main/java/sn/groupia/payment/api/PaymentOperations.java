package sn.groupia.payment.api;

import java.util.UUID;
import sn.groupia.payment.api.dto.*;

public interface PaymentOperations {
    PaymentSettlementEvidence settlementEvidence(UUID organizationId, UUID paymentRequestId);

    boolean providerAvailable(String provider);

    PaymentRequestState requestState(UUID org, UUID request);

    PaymentRequestState lockRequestState(UUID org, UUID request);

    PaymentAttemptView initiateRequest(
            UUID organizationId,
            UUID paymentRequestId,
            String provider,
            String idempotencyKey,
            PaymentCheckoutContext context);

    /**
     * Direct-only, capability-verified initiation. The token hash is rechecked under the order
     * lock by DirectPaymentClaimGuard; the generic initiateRequest route remains unusable
     * for direct orders, even when the explicit feature flag is enabled.
     */
    PaymentAttemptView initiateDirect(
            UUID organizationId,
            UUID paymentRequestId,
            String idempotencyKey,
            String recoveryTokenHash,
            PaymentCheckoutContext context);

    PaymentRequestView request(UUID organizationId, UUID paymentRequestId);

    java.util.List<PaymentObligationView> shippingRequests(UUID organizationId, UUID orderId);

    /** Caller holds the order lock. Retains request locks until the caller transaction commits. */
    java.util.List<PaymentObligationView> lockShippingRequests(UUID organizationId, UUID orderId);

    void cancelShippingRequest(UUID organizationId, UUID paymentRequestId);

    /** Caller holds the direct order lock; rejects attempts, reviews and settled requests. */
    void cancelDirectUnpaidRequest(UUID organizationId, UUID paymentRequestId, UUID directOrderId);

    PaymentRequestView createRequest(CreatePaymentRequest command);

    PaymentRequestView initialRequest(UUID organizationId, UUID orderId);

    PaymentAttemptView initiateInitial(
            UUID organizationId, UUID orderId, String provider, String idempotencyKey);

    PaymentAttemptView initiateInitial(
            UUID organizationId,
            UUID orderId,
            String provider,
            String idempotencyKey,
            PaymentCheckoutContext context);

    /** False if already settled: expiration must wait for the durable settlement event. */
    boolean cancelInitialAcceptance(UUID organizationId, UUID orderId);
}
