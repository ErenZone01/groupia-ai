package sn.groupia.payment.domain.service;

import java.math.BigDecimal;
import java.util.UUID;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.PaymentCheckoutContext;

public record ProviderInstruction(
        UUID paymentId,
        UUID paymentRequestId,
        UUID orderId,
        PaymentPurpose purpose,
        String provider,
        String providerAccountReference,
        String merchantReference,
        String providerTransactionId,
        String providerIdempotencyKey,
        BigDecimal amount,
        String currency,
        PaymentCheckoutContext checkout,
        PaymentTarget target) {
    public ProviderInstruction(
            UUID paymentId,
            UUID request,
            UUID order,
            PaymentPurpose purpose,
            String provider,
            String account,
            String merchant,
            String transaction,
            String key,
            BigDecimal amount,
            String currency,
            PaymentCheckoutContext checkout) {
        this(
                paymentId,
                request,
                order,
                purpose,
                provider,
                account,
                merchant,
                transaction,
                key,
                amount,
                currency,
                checkout,
                order == null ? null : PaymentTarget.order(order));
    }

    public ProviderInstruction(
            UUID paymentId,
            UUID request,
            UUID order,
            PaymentPurpose purpose,
            String provider,
            String account,
            String merchant,
            String transaction,
            String key,
            BigDecimal amount,
            String currency) {
        this(
                paymentId,
                request,
                order,
                purpose,
                provider,
                account,
                merchant,
                transaction,
                key,
                amount,
                currency,
                null);
    }

    public ProviderInstruction withTransactionId(String id) {
        return new ProviderInstruction(
                paymentId,
                paymentRequestId,
                orderId,
                purpose,
                provider,
                providerAccountReference,
                merchantReference,
                id,
                providerIdempotencyKey,
                amount,
                currency,
                checkout,
                target);
    }

    public ProviderInstruction withCheckout(PaymentCheckoutContext context) {
        return new ProviderInstruction(
                paymentId,
                paymentRequestId,
                orderId,
                purpose,
                provider,
                providerAccountReference,
                merchantReference,
                providerTransactionId,
                providerIdempotencyKey,
                amount,
                currency,
                context,
                target);
    }

    @Override
    public String toString() {
        return "ProviderInstruction[redacted]";
    }
}
