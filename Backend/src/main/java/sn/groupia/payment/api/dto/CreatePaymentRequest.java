package sn.groupia.payment.api.dto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.payment.api.PaymentPurpose;
public record CreatePaymentRequest(UUID organizationId, UUID orderId, PaymentPurpose purpose, String businessReference,
        BigDecimal expectedAmount, String currency, BigDecimal goodsAmount, BigDecimal feeAmount,
        BigDecimal shippingAmount, Instant expiresAt) {}
