package sn.groupia.payment.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.application.service.PaymentReviewService;
import sn.groupia.shared.api.ApiException;

@RestController
@RequestMapping("/api/organizations/{organizationId}/payment-reviews")
class PaymentReviewController {
    private final PaymentReviewService reviews;

    PaymentReviewController(PaymentReviewService reviews) {
        this.reviews = reviews;
    }

    record ResolvePaymentReviewRefunded(String externalRefundReference, String resolutionNote) {
        @JsonAnySetter
        public void reject(String key, Object value) {
            throw new ApiException(400, "INVALID_REQUEST", "Champ de résolution non autorisé.");
        }
    }

    @GetMapping
    @Operation(operationId = "listPaymentReviews", summary = "Anomalies de paiement à examiner")
    ResponseEntity<PaymentReviewPage> list(
            @PathVariable UUID organizationId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID paymentRequestId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return response(reviews.list(organizationId, status, paymentRequestId, page, size));
    }

    @GetMapping("/{reviewId}")
    @Operation(operationId = "getPaymentReview", summary = "Consulter une anomalie de paiement")
    ResponseEntity<PaymentReviewView> get(
            @PathVariable UUID organizationId, @PathVariable UUID reviewId) {
        return response(reviews.get(organizationId, reviewId));
    }

    @PostMapping("/{reviewId}/resolve-refunded")
    @Operation(
            operationId = "resolvePaymentReviewRefunded",
            summary = "Constater un remboursement externe déjà effectué")
    ResponseEntity<PaymentReviewView> resolve(
            @PathVariable UUID organizationId,
            @PathVariable UUID reviewId,
            @RequestBody ResolvePaymentReviewRefunded body) {
        return response(
                reviews.resolve(
                        organizationId,
                        reviewId,
                        body.externalRefundReference(),
                        body.resolutionNote()));
    }

    private static <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }
}
