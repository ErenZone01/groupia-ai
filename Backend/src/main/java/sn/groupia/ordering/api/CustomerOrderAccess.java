package sn.groupia.ordering.api;

import java.util.UUID;
import sn.groupia.ordering.api.dto.*;

/**
 * Internal capability boundary: customeraccess verifies the session/phone before invoking these
 * queries.
 */
public interface CustomerOrderAccess {
    java.util.List<CustomerFeeQuote> quoteShippingBalance(UUID organizationId, UUID orderId);

    CustomerPaymentOptions shippingPaymentOptions(UUID organizationId, UUID orderId);

    CustomerPaymentAttempt initiateShippingBalance(
            UUID organizationId,
            UUID orderId,
            String provider,
            String idempotencyKey,
            UUID feeQuoteId);

    default CustomerPaymentAttempt initiateShippingBalance(
            UUID organizationId,
            UUID orderId,
            String provider,
            String idempotencyKey,
            UUID feeQuoteId,
            String payerEmail) {
        if (payerEmail != null && !payerEmail.isBlank())
            throw new UnsupportedOperationException("Hosted payer contact not supported");
        return initiateShippingBalance(
                organizationId, orderId, provider, idempotencyKey, feeQuoteId);
    }

    CustomerPaymentAttempt initiateShippingBalance(
            UUID organizationId, UUID orderId, String provider, String idempotencyKey);

    CustomerOrderIdentity identity(UUID organizationId, UUID orderId);

    CustomerOrderIdentity forPhone(String verifiedPhone, String orderNumber);

    MyOrdersPage findOrdersForVerifiedPhone(String verifiedPhone, int page, int size);

    OrderTrackingView track(UUID organizationId, UUID orderId);
}
