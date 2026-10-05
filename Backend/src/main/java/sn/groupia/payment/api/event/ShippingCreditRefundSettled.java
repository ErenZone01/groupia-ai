package sn.groupia.payment.api.event;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record ShippingCreditRefundSettled(UUID eventId,Instant occurredAt,UUID organizationId,UUID orderId,
    UUID refundId,BigDecimal amount,String currency) {}

