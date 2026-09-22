package sn.groupia.payment.api;
import java.util.UUID;
import sn.groupia.payment.api.dto.*;
public interface PaymentOperations {
    PaymentRequestView createRequest(CreatePaymentRequest command);
    PaymentRequestView initialRequest(UUID organizationId, UUID orderId);
    PaymentAttemptView initiateInitial(UUID organizationId, UUID orderId, String provider, String idempotencyKey);
    PaymentAttemptView initiateInitial(UUID organizationId, UUID orderId, String provider, String idempotencyKey, PaymentCheckoutContext context);
    /** False if already settled: expiration must wait for the durable settlement event. */
    boolean cancelInitialAcceptance(UUID organizationId, UUID orderId);
}
