package sn.groupia.payment.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentReviewView(
        UUID id,
        UUID organizationId,
        UUID paymentRequestId,
        UUID orderId,
        UUID principalPaymentId,
        UUID anomalousPaymentId,
        String reason,
        String status,
        String anomalousProvider,
        String anomalousProviderAccountReference,
        String anomalousProviderTransactionId,
        BigDecimal observedAmount,
        String observedCurrency,
        Instant providerOccurredAt,
        Instant detectedAt,
        Instant resolvedAt,
        UUID resolvedBy,
        String externalRefundReference,
        String resolutionNote,
        long version) {}
