package sn.groupia.subscription.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SubscriptionPeriod(
        UUID id,
        UUID organizationId,
        UUID subscriptionId,
        String planCode,
        BigDecimal amount,
        String currency,
        String billingInterval,
        Instant periodStart,
        Instant periodEnd,
        String provider,
        String providerTransactionId,
        String providerReference,
        String status,
        Instant paidAt,
        Instant createdAt,
        UUID subscriptionInvoiceId,
        UUID paymentRequestId,
        UUID paymentId,
        String providerAccountReference) {
    public SubscriptionPeriod(
            UUID id,
            UUID org,
            UUID subscription,
            String plan,
            BigDecimal amount,
            String currency,
            String interval,
            Instant start,
            Instant end,
            String provider,
            String transaction,
            String reference,
            String status,
            Instant paidAt,
            Instant createdAt) {
        this(
                id,
                org,
                subscription,
                plan,
                amount,
                currency,
                interval,
                start,
                end,
                provider,
                transaction,
                reference,
                status,
                paidAt,
                createdAt,
                null,
                null,
                null,
                null);
    }
}
