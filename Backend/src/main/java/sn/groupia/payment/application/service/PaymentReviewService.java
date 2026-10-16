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

    public PaymentReviewPage list(
            UUID org, String status, UUID paymentRequestId, int page, int size) {
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
        var result =
                reviews.list(
                        org,
                        filter,
                        paymentRequestId,
                        page,
                        size,
                        access.hasPermission(org, OrganizationPermission.VIEW_SUBSCRIPTION));
        boolean allowed = access.hasPermission(org, OrganizationPermission.MANAGE_REFUNDS);
        return new PaymentReviewPage(
                result.items().stream().map(r -> view(r, allowed)).toList(),
                page,
                size,
                result.total(),
                (result.total() + size - 1) / size);
    }

    public PaymentReviewView get(UUID org, UUID id) {
        access.requirePermission(org, OrganizationPermission.VIEW_PAYMENTS);
        return view(
                require(org, id), access.hasPermission(org, OrganizationPermission.MANAGE_REFUNDS));
    }

    @Transactional
    public PaymentReviewView resolve(UUID org, UUID id, String reference, String note) {
        UUID actor = access.requireActorPermission(org, OrganizationPermission.MANAGE_REFUNDS);
        var initial = require(org, id);
        var target = payments.request(initial.getPaymentRequestId()).orElseThrow().getTarget();
        if (target.type() == sn.groupia.payment.api.PaymentTargetType.SUBSCRIPTION_INVOICE)
            access.requirePermission(org, OrganizationPermission.MANAGE_SUBSCRIPTION);
        payments.lockRequest(initial.getPaymentRequestId());
        var payment = payments.lockPayment(initial.getAnomalousPaymentId());
        var review = reviews.lock(org, id);
        review.resolveRefunded(reference, note, actor, clock.instant());
        reviews.flush();
        if (!reviews.hasOpen(payment.getId())) payment.clearDuplicateReview();
        payments.flush();
        return view(review, true);
    }

    private PaymentReview require(UUID org, UUID id) {
        return reviews.find(org, id)
                .filter(
                        r ->
                                payments.request(r.getPaymentRequestId())
                                                        .orElseThrow()
                                                        .getTarget()
                                                        .type()
                                                == sn.groupia.payment.api.PaymentTargetType.ORDER
                                        || access.hasPermission(
                                                org, OrganizationPermission.VIEW_SUBSCRIPTION))
                .orElseThrow(
                        () ->
                                new ApiException(
                                        404, "PAYMENT_REVIEW_NOT_FOUND", "Anomalie introuvable."));
    }

    private PaymentReviewView view(PaymentReview review, boolean allowed) {
        var target = payments.request(review.getPaymentRequestId()).orElseThrow().getTarget();
        return review.view(target)
                .withCanResolve(
                        allowed
                                && review.getStatus() == PaymentReviewStatus.OPEN
                                && (target.type() == sn.groupia.payment.api.PaymentTargetType.ORDER
                                        || access.hasPermission(
                                                review.getOrganizationId(),
                                                OrganizationPermission.MANAGE_SUBSCRIPTION)));
    }
}
