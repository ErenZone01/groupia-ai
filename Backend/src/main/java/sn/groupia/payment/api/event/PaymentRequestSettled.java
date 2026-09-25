package sn.groupia.payment.api.event;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.payment.api.PaymentPurpose;
public record PaymentRequestSettled(UUID eventId, Instant occurredAt, UUID organizationId, UUID orderId,
        UUID paymentRequestId, UUID paymentId, PaymentPurpose purpose, BigDecimal amount, String currency) {}
