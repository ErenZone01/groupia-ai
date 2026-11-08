package sn.groupia.payment.api.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.payment.api.PaymentTarget;

/** Records an authorized manual declaration, not a verified provider refund. */
public record CommercialRefundRecorded(
        UUID eventId,
        Instant occurredAt,
        UUID organizationId,
        PaymentTarget target,
        UUID caseId,
        BigDecimal amount,
        String currency) {}
