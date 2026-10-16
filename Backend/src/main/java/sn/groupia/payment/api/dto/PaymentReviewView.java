package sn.groupia.payment.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentReviewView(
        UUID id,
        UUID organizationId,
        UUID paymentRequestId,
        @io.swagger.v3.oas.annotations.media.Schema(
                        nullable = true,
                        description = "Présent uniquement pour une cible ORDER.")
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
        long version,
        boolean canResolve,
        sn.groupia.payment.api.PaymentTarget target) {
    public PaymentReviewView withCanResolve(boolean allowed) {
        return new PaymentReviewView(
                id,
                organizationId,
                paymentRequestId,
                orderId,
                principalPaymentId,
                anomalousPaymentId,
                reason,
                status,
                anomalousProvider,
                anomalousProviderAccountReference,
                anomalousProviderTransactionId,
                observedAmount,
                observedCurrency,
                providerOccurredAt,
                detectedAt,
                resolvedAt,
                resolvedBy,
                externalRefundReference,
                resolutionNote,
                version,
                allowed,
                target);
    }
}
