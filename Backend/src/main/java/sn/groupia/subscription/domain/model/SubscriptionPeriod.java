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
        Instant createdAt) {}
