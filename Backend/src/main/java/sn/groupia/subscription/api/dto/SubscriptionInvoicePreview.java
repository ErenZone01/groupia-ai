package sn.groupia.subscription.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record SubscriptionInvoicePreview(
        Instant periodStart,
        Instant periodEnd,
        String billingTimezone,
        BigDecimal amount,
        String currency) {}
