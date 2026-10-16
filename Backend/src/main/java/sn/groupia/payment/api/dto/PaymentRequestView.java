package sn.groupia.payment.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.payment.api.*;

public record PaymentRequestView(
        UUID id,
        UUID organizationId,
        @io.swagger.v3.oas.annotations.media.Schema(
                        nullable = true,
                        description = "Présent uniquement pour une cible ORDER.")
                UUID orderId,
        PaymentPurpose purpose,
        String businessReference,
        BigDecimal expectedAmount,
        String currency,
        BigDecimal goodsAmount,
        BigDecimal feeAmount,
        BigDecimal shippingAmount,
        AcceptanceStatus acceptanceStatus,
        @io.swagger.v3.oas.annotations.media.Schema(
                        nullable = true,
                        format = "date-time",
                        description =
                                "Deadline obligatoire pour INITIAL_ORDER ; null autorisé pour SHIPPING_BALANCE.")
                Instant expiresAt,
        UUID settledPaymentId,
        Instant createdAt,
        Instant closedAt,
        Instant cancelledAt,
        long version,
        PaymentTarget target) {}
