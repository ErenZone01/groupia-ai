package sn.groupia.payment.api;

import java.util.UUID;

/** Locks campaign then order before the payment request. Result only controls NEW attempts. */
public interface OrderPaymentClaimGuard {
    boolean lockAndCanInitiate(UUID organizationId, UUID orderId, PaymentPurpose purpose);
}
