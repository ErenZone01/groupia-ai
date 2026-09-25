package sn.groupia.payment.api.dto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.payment.api.*;
public record PaymentRequestView(UUID id, UUID organizationId, UUID orderId, PaymentPurpose purpose,
        String businessReference, BigDecimal expectedAmount, String currency, BigDecimal goodsAmount,
        BigDecimal feeAmount, BigDecimal shippingAmount, AcceptanceStatus acceptanceStatus,
        Instant expiresAt, UUID settledPaymentId, Instant createdAt, Instant closedAt, Instant cancelledAt, long version) {}
