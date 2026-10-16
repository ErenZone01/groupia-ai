package sn.groupia.payment.api.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.payment.api.*;

public record PaymentObligationSettled(
        UUID eventId,
        Instant occurredAt,
        UUID organizationId,
        PaymentTarget target,
        UUID paymentRequestId,
        UUID paymentId,
        PaymentPurpose purpose,
        BigDecimal amount,
        String currency) {}
