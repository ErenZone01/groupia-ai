package sn.groupia.payment.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.payment.api.*;

/** Server evidence of the immutable principal; no checkout or customer information. */
public record PaymentSettlementEvidence(
        UUID organizationId,
        UUID paymentRequestId,
        UUID paymentId,
        PaymentTarget target,
        PaymentPurpose purpose,
        BigDecimal amount,
        String currency,
        AcceptanceStatus acceptanceStatus,
        PaymentStatus status,
        String provider,
        String providerAccountReference,
        String providerTransactionId,
        String paymentReference,
        Instant confirmedAt,
        Instant expiresAt,
        boolean reversal) {}
