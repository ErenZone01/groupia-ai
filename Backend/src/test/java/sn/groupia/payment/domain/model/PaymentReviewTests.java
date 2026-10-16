package sn.groupia.payment.domain.model;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.CreatePaymentRequest;
import sn.groupia.payment.domain.service.ProviderObservation;
import sn.groupia.shared.api.ApiException;

class PaymentReviewTests {
    private final Instant now = Instant.parse("2026-10-11T10:00:00Z");
    private final UUID actor = UUID.randomUUID();

    private PaymentReview review() {
        var request =
                PaymentRequest.create(
                        new CreatePaymentRequest(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                PaymentPurpose.INITIAL_ORDER,
                                "INITIAL_ORDER:" + UUID.randomUUID(),
                                new BigDecimal("1000"),
                                "XOF",
                                new BigDecimal("1000"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                now.plusSeconds(1800)),
                        now);
        var payment = Payment.create(request, "SIMULATED", null, UUID.randomUUID().toString(), now);
        request.allocate(payment.getId(), now);
        return PaymentReview.duplicate(
                request,
                payment,
                new ProviderObservation(
                        "SIMULATED",
                        null,
                        payment.getMerchantReference(),
                        "TX-B",
                        request.getId(),
                        request.getOrderId(),
                        PaymentPurpose.INITIAL_ORDER,
                        new BigDecimal("1000"),
                        "XOF",
                        PaymentStatus.SUCCEEDED,
                        null),
                now);
    }

    @Test
    void incidentSnapshotsSupportSameAttemptAndUnknownProviderTime() {
        var view = review().view(PaymentTarget.order(UUID.randomUUID()));
        assertThat(view.principalPaymentId()).isEqualTo(view.anomalousPaymentId());
        assertThat(view.providerOccurredAt()).isNull();
        assertThat(view.observedAmount()).isEqualByComparingTo("1000");
        assertThat(view.status()).isEqualTo("OPEN");
        assertThat(view.anomalousProviderTransactionId()).isEqualTo("TX-B");
    }

    @Test
    void resolutionNormalizesAndPreservesFirstActorAndDate() {
        var review = review();
        UUID order = UUID.randomUUID();
        review.resolveRefunded(" REF ", " ", actor, now.plusSeconds(1));
        var first = review.view(PaymentTarget.order(order));
        review.resolveRefunded("REF", null, UUID.randomUUID(), now.plusSeconds(20));
        assertThat(review.view(PaymentTarget.order(order))).isEqualTo(first);
        assertThat(first.resolutionNote()).isNull();
        assertThat(first.resolvedBy()).isEqualTo(actor);
        assertThat(first.resolvedAt()).isEqualTo(now.plusSeconds(1));
    }

    @Test
    void contradictoryResolutionNeverRewritesHistory() {
        var review = review();
        review.resolveRefunded("REF", "note", actor, now);
        assertThatThrownBy(() -> review.resolveRefunded("OTHER", "note", actor, now))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> review.resolveRefunded("REF", "other", actor, now))
                .isInstanceOf(ApiException.class);
        assertThat(review.view(PaymentTarget.order(UUID.randomUUID())).externalRefundReference())
                .isEqualTo("REF");
    }

    @Test
    void invalidResolutionLeavesIncidentOpen() {
        var review = review();
        for (String reference : new String[] {null, " ", "x".repeat(161)})
            assertThatThrownBy(() -> review.resolveRefunded(reference, null, actor, now))
                    .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> review.resolveRefunded("REF", "x".repeat(1001), actor, now))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> review.resolveRefunded("REF", null, actor, now.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(review.getStatus()).isEqualTo(PaymentReviewStatus.OPEN);
    }
}
