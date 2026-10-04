package sn.groupia.payment.api;
import java.util.UUID;
import sn.groupia.payment.api.dto.*;
public interface PaymentOperations {
    PaymentAttemptView initiateRequest(UUID organizationId, UUID paymentRequestId, String provider, String idempotencyKey, PaymentCheckoutContext context);
    PaymentRequestView request(UUID organizationId, UUID paymentRequestId);
    java.util.List<PaymentObligationView> shippingRequests(UUID organizationId, UUID orderId);
    /** Caller holds the order lock. Retains request locks until the caller transaction commits. */
    java.util.List<PaymentObligationView> lockShippingRequests(UUID organizationId, UUID orderId);
    void cancelShippingRequest(UUID organizationId, UUID paymentRequestId);
    PaymentRequestView createRequest(CreatePaymentRequest command);
    PaymentRequestView initialRequest(UUID organizationId, UUID orderId);
    PaymentAttemptView initiateInitial(UUID organizationId, UUID orderId, String provider, String idempotencyKey);
    PaymentAttemptView initiateInitial(UUID organizationId, UUID orderId, String provider, String idempotencyKey, PaymentCheckoutContext context);
    /** False if already settled: expiration must wait for the durable settlement event. */
    boolean cancelInitialAcceptance(UUID organizationId, UUID orderId);
}
