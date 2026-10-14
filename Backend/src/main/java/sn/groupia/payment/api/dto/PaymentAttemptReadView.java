package sn.groupia.payment.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.payment.api.PaymentStatus;

public record PaymentAttemptReadView(
        UUID id,
        String provider,
        String providerTransactionId,
        PaymentStatus status,
        BigDecimal expectedAmount,
        String currency,
        BigDecimal confirmedAmount,
        String confirmedCurrency,
        boolean reconciliationRequired,
        String reconciliationReason,
        Instant initiatedAt,
        Instant confirmedAt,
        Instant lastCheckedAt) {}
