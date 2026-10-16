package sn.groupia.payment.domain.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.payment.api.*;

public record ProviderObservation(
        String provider,
        String providerAccountReference,
        String merchantReference,
        String providerTransactionId,
        UUID paymentRequestId,
        UUID orderId,
        PaymentPurpose purpose,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        Instant occurredAt,
        ProviderMetadata metadata,
        PaymentTarget target) {
    public ProviderObservation {
        // Receipts persisted before target was introduced retain their original ORDER evidence.
        if (target == null
                && orderId != null
                && (purpose == PaymentPurpose.INITIAL_ORDER
                        || purpose == PaymentPurpose.SHIPPING_BALANCE))
            target = PaymentTarget.order(orderId);
    }

    public ProviderObservation(
            String provider,
            String account,
            String merchant,
            String transaction,
            UUID request,
            UUID order,
            PaymentPurpose purpose,
            BigDecimal amount,
            String currency,
            PaymentStatus status,
            Instant at,
            ProviderMetadata metadata) {
        this(
                provider,
                account,
                merchant,
                transaction,
                request,
                order,
                purpose,
                amount,
                currency,
                status,
                at,
                metadata,
                order == null ? null : PaymentTarget.order(order));
    }

    public ProviderObservation(
            String provider,
            String account,
            String merchant,
            String transaction,
            UUID request,
            UUID order,
            PaymentPurpose purpose,
            BigDecimal amount,
            String currency,
            PaymentStatus status,
            Instant at) {
        this(
                provider,
                account,
                merchant,
                transaction,
                request,
                order,
                purpose,
                amount,
                currency,
                status,
                at,
                null);
    }

    /**
     * Bind provider-neutral references only after resolving the authenticated merchant reference.
     */
    public ProviderObservation correlate(ProviderInstruction i) {
        if (paymentRequestId != null || orderId != null || purpose != null || target != null) {
            return this;
        }
        return new ProviderObservation(
                provider,
                providerAccountReference,
                merchantReference,
                providerTransactionId,
                i.paymentRequestId(),
                i.orderId(),
                i.purpose(),
                amount,
                currency,
                status,
                occurredAt,
                metadata,
                i.target());
    }

    @Override
    public String toString() {
        return "ProviderObservation[redacted]";
    }
}
