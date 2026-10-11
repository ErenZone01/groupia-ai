package sn.groupia.payment.application.service;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.organization.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.domain.model.*;
import sn.groupia.payment.domain.repository.*;
import sn.groupia.shared.api.ApiException;

@Service
@Transactional(readOnly = true)
public class PaymentReviewService {
    private final PaymentReviewStore reviews;
    private final FinancialStore payments;
    private final OrganizationAccess access;
    private final Clock clock;

    PaymentReviewService(
            PaymentReviewStore reviews,
            FinancialStore payments,
            OrganizationAccess access,
            Clock clock) {
        this.reviews = reviews;
        this.payments = payments;
        this.access = access;
        this.clock = clock;
    }

    public PaymentReviewPage list(UUID org, String status, int page, int size) {
        access.requirePermission(org, OrganizationPermission.VIEW_PAYMENTS);
        if (page < 0 || page > 1000000 || size < 1 || size > 100)
            throw new ApiException(400, "INVALID_REQUEST", "Pagination invalide.");
        PaymentReviewStatus filter = null;
        if (status != null) {
            try {
                filter = PaymentReviewStatus.valueOf(status);
            } catch (IllegalArgumentException ex) {
                throw new ApiException(400, "INVALID_REQUEST", "Statut invalide.");
            }
        }
        var result = reviews.list(org, filter, page, size);
        return new PaymentReviewPage(
                result.items().stream().map(this::view).toList(),
                page,
                size,
                result.total(),
                (result.total() + size - 1) / size);
    }

    public PaymentReviewView get(UUID org, UUID id) {
        access.requirePermission(org, OrganizationPermission.VIEW_PAYMENTS);
        return view(require(org, id));
    }

    @Transactional
    public PaymentReviewView resolve(UUID org, UUID id, String reference, String note) {
        UUID actor = access.requireActorPermission(org, OrganizationPermission.MANAGE_REFUNDS);
        var initial = require(org, id);
        payments.lockRequest(initial.getPaymentRequestId());
        var payment = payments.lockPayment(initial.getAnomalousPaymentId());
        var review = reviews.lock(org, id);
        review.resolveRefunded(reference, note, actor, clock.instant());
        reviews.flush();
        if (!reviews.hasOpen(payment.getId())) payment.clearDuplicateReview();
        payments.flush();
        return view(review);
    }

    private PaymentReview require(UUID org, UUID id) {
        return reviews.find(org, id)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        404, "PAYMENT_REVIEW_NOT_FOUND", "Anomalie introuvable."));
    }

    private PaymentReviewView view(PaymentReview review) {
        return review.view(
                payments.request(review.getPaymentRequestId()).orElseThrow().getOrderId());
    }
}
