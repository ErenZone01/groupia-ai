package sn.groupia.payment.domain.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import sn.groupia.payment.api.dto.PaymentReviewView;
import sn.groupia.payment.domain.service.ProviderObservation;
import sn.groupia.shared.api.ApiException;

@Entity
@Table(name = "payment_reviews")
public class PaymentReview {
    public static final String DUPLICATE = "DUPLICATE_PROVIDER_SUCCESS";
    @Id private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false, updatable = false)
    private UUID paymentRequestId;

    @Column(nullable = false, updatable = false)
    private UUID principalPaymentId;

    @Column(nullable = false, updatable = false)
    private UUID anomalousPaymentId;

    @Column(nullable = false, updatable = false, length = 64)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentReviewStatus status;

    @Column(nullable = false, updatable = false, length = 64)
    private String anomalousProvider;

    @Column(updatable = false, length = 128)
    private String anomalousProviderAccountReference;

    @Column(nullable = false, updatable = false, length = 160)
    private String anomalousProviderTransactionId;

    @Column(nullable = false, updatable = false, columnDefinition = "numeric")
    private BigDecimal observedAmount;

    @Column(nullable = false, updatable = false, length = 3)
    private String observedCurrency;

    @Column(updatable = false)
    private Instant providerOccurredAt;

    @Column(nullable = false, updatable = false)
    private Instant detectedAt;

    private Instant resolvedAt;
    private UUID resolvedBy;

    @Column(length = 160)
    private String externalRefundReference;

    @Column(length = 1000)
    private String resolutionNote;

    @Version private Long version;

    protected PaymentReview() {}

    public static PaymentReview duplicate(
            PaymentRequest request, Payment attempt, ProviderObservation observation, Instant now) {
        if (request.getSettledPaymentId() == null
                || !request.getId().equals(attempt.getPaymentRequestId())) {
            throw new IllegalArgumentException("A duplicate requires an allocated obligation");
        }
        var review = new PaymentReview();
        review.id = UUID.randomUUID();
        review.organizationId = request.getOrganizationId();
        review.paymentRequestId = request.getId();
        review.principalPaymentId = request.getSettledPaymentId();
        review.anomalousPaymentId = attempt.getId();
        review.reason = DUPLICATE;
        review.status = PaymentReviewStatus.OPEN;
        review.anomalousProvider = observation.provider();
        review.anomalousProviderAccountReference = observation.providerAccountReference();
        review.anomalousProviderTransactionId = observation.providerTransactionId();
        review.observedAmount = observation.amount();
        review.observedCurrency = observation.currency();
        review.providerOccurredAt = observation.occurredAt();
        review.detectedAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return review;
    }

    public void resolveRefunded(String reference, String note, UUID actor, Instant now) {
        reference = reference == null ? null : reference.strip();
        note = note == null ? null : note.strip();
        if (note != null && note.isEmpty()) note = null;
        if (reference == null
                || reference.isEmpty()
                || reference.length() > 160
                || (note != null && note.length() > 1000)) {
            throw new ApiException(
                    400, "INVALID_REQUEST", "Référence ou note de résolution invalide.");
        }
        if (status == PaymentReviewStatus.RESOLVED_REFUNDED) {
            if (!reference.equals(externalRefundReference)
                    || !Objects.equals(note, resolutionNote)) {
                throw new ApiException(
                        409,
                        "PAYMENT_REVIEW_RESOLUTION_CONFLICT",
                        "Cette anomalie possède déjà une autre résolution.");
            }
            return;
        }
        Objects.requireNonNull(actor);
        Objects.requireNonNull(now);
        now = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (now.isBefore(detectedAt))
            throw new IllegalArgumentException("Resolution precedes detection");
        status = PaymentReviewStatus.RESOLVED_REFUNDED;
        externalRefundReference = reference;
        resolutionNote = note;
        resolvedBy = actor;
        resolvedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getPaymentRequestId() {
        return paymentRequestId;
    }

    public UUID getAnomalousPaymentId() {
        return anomalousPaymentId;
    }

    public PaymentReviewStatus getStatus() {
        return status;
    }

    public PaymentReviewView view(UUID orderId) {
        return new PaymentReviewView(
                id,
                organizationId,
                paymentRequestId,
                orderId,
                principalPaymentId,
                anomalousPaymentId,
                reason,
                status.name(),
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
                version == null ? 0 : version);
    }
}
