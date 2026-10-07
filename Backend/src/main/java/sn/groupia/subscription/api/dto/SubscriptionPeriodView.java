package sn.groupia.subscription.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record SubscriptionPeriodView(
        String planCode,
        BigDecimal amount,
        String currency,
        String billingInterval,
        Instant periodStart,
        Instant periodEnd,
        String status,
        Instant paidAt) {}
