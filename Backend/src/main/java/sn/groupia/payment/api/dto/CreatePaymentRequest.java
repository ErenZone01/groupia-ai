package sn.groupia.payment.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.payment.api.*;

public record CreatePaymentRequest(
        UUID organizationId,
        PaymentTarget target,
        PaymentPurpose purpose,
        String businessReference,
        BigDecimal expectedAmount,
        String currency,
        BigDecimal goodsAmount,
        BigDecimal feeAmount,
        BigDecimal shippingAmount,
        Instant expiresAt) {
    /** Historical order callers. New callers should use the named factories. */
    public CreatePaymentRequest(
            UUID org,
            UUID order,
            PaymentPurpose purpose,
            String reference,
            BigDecimal amount,
            String currency,
            BigDecimal goods,
            BigDecimal fee,
            BigDecimal shipping,
            Instant expires) {
        this(
                org,
                order == null ? null : PaymentTarget.order(order),
                purpose,
                reference,
                amount,
                currency,
                goods,
                fee,
                shipping,
                expires);
    }

    public static CreatePaymentRequest forOrder(
            UUID org,
            UUID order,
            PaymentPurpose purpose,
            String reference,
            BigDecimal amount,
            String currency,
            BigDecimal goods,
            BigDecimal fee,
            BigDecimal shipping,
            Instant expires) {
        return new CreatePaymentRequest(
                org,
                PaymentTarget.order(order),
                purpose,
                reference,
                amount,
                currency,
                goods,
                fee,
                shipping,
                expires);
    }

    /**
     * Draft financial target only. A separate authoritative direct-sales application service must
     * verify the order snapshots before requesting this obligation; payment stays disabled.
     */
    public static CreatePaymentRequest forDirectOrder(
            UUID org,
            UUID directOrder,
            BigDecimal goods,
            BigDecimal fee,
            BigDecimal shipping,
            Instant expires) {
        return new CreatePaymentRequest(
                org,
                PaymentTarget.directOrder(directOrder),
                PaymentPurpose.DIRECT_ORDER_INITIAL,
                "DIRECT_ORDER_INITIAL:" + directOrder,
                goods.add(fee).add(shipping),
                "XOF",
                goods,
                fee,
                shipping,
                expires);
    }

    public static CreatePaymentRequest forSubscription(
            UUID org, UUID invoice, BigDecimal amount, Instant expires) {
        return new CreatePaymentRequest(
                org,
                PaymentTarget.subscription(invoice),
                PaymentPurpose.SUBSCRIPTION_MONTHLY,
                "SUBSCRIPTION_MONTHLY:" + invoice,
                amount,
                "XOF",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                expires);
    }

    public UUID orderId() {
        return target != null && target.type() == PaymentTargetType.ORDER ? target.id() : null;
    }
}
