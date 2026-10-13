package sn.groupia.payment.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import sn.groupia.payment.api.*;

public record PaymentRequestSummaryView(
        UUID id,
        UUID organizationId,
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
                        description = "Sans échéance possible pour le transport.")
                Instant expiresAt,
        UUID settledPaymentId,
        Instant createdAt,
        Instant closedAt,
        Instant cancelledAt,
        long version,
        PaymentOperationalStatus operationalStatus,
        Set<String> operationalReasons,
        @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String terminalReason,
        long openReviewCount,
        boolean attemptInProgress,
        boolean reconciliationRequired) {}
