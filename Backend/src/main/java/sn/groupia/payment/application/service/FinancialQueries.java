package sn.groupia.payment.application.service;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.organization.api.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.domain.model.*;
import sn.groupia.payment.domain.repository.*;
import sn.groupia.payment.domain.service.*;
import sn.groupia.shared.api.ApiException;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class FinancialQueries {
    private final FinancialStore store;
    private final PaymentDashboardStore dashboard;
    private final PaymentReviewStore reviews;
    private final OrganizationAccess access;
    private final Clock clock;

    FinancialQueries(
            FinancialStore store,
            PaymentDashboardStore dashboard,
            PaymentReviewStore reviews,
            OrganizationAccess access,
            Clock clock) {
        this.store = store;
        this.dashboard = dashboard;
        this.reviews = reviews;
        this.access = access;
        this.clock = clock;
    }

    public PaymentRequestsPageView list(
            UUID org,
            PaymentOperationalStatus status,
            PaymentPurpose purpose,
            UUID orderId,
            int page,
            int size) {
        access.requirePermission(org, OrganizationPermission.VIEW_PAYMENTS);
        if (page < 0 || page > 1000000 || size < 1 || size > 100)
            throw new ApiException(400, "INVALID_REQUEST", "Pagination invalide.");
        Instant at = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var result =
                dashboard.list(
                        org,
                        status,
                        purpose,
                        orderId,
                        page,
                        size,
                        at,
                        access.hasPermission(org, OrganizationPermission.VIEW_SUBSCRIPTION));
        var facts =
                dashboard.facts(result.rows().stream().map(PaymentDashboardStore.Row::id).toList());
        var requests =
                facts.requests().stream()
                        .collect(Collectors.toMap(PaymentRequest::getId, Function.identity()));
        var attempts =
                facts.attempts().stream()
                        .collect(Collectors.groupingBy(Payment::getPaymentRequestId));
        var incidents =
                facts.reviews().stream()
                        .collect(Collectors.groupingBy(PaymentReview::getPaymentRequestId));
        var items =
                result.rows().stream()
                        .map(
                                row -> {
                                    var view =
                                            summary(
                                                    requests.get(row.id()),
                                                    attempts.getOrDefault(row.id(), List.of()),
                                                    incidents.getOrDefault(row.id(), List.of()),
                                                    at);
                                    if (view.operationalStatus() != row.status())
                                        throw new IllegalStateException(
                                                "Payment dashboard classification parity violated");
                                    return view;
                                })
                        .toList();
        return new PaymentRequestsPageView(
                items,
                page,
                size,
                result.total(),
                (result.total() + size - 1) / size,
                result.summary(),
                at);
    }

    public PaymentRequestDetailView get(UUID org, UUID id) {
        access.requirePermission(org, OrganizationPermission.VIEW_PAYMENTS);
        Instant at = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var request =
                store.request(id)
                        .filter(r -> r.getOrganizationId().equals(org))
                        .filter(
                                r ->
                                        r.getTarget().type() == PaymentTargetType.ORDER
                                                || access.hasPermission(
                                                        org,
                                                        OrganizationPermission.VIEW_SUBSCRIPTION))
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                404,
                                                "PAYMENT_REQUEST_NOT_FOUND",
                                                "Obligation introuvable."));
        var attempts = store.attempts(id);
        return new PaymentRequestDetailView(
                summary(request, attempts, reviews.forRequest(id), at),
                attempts.stream()
                        .map(
                                p ->
                                        new PaymentAttemptReadView(
                                                p.getId(),
                                                p.getProvider(),
                                                p.getProviderTransactionId(),
                                                p.getStatus(),
                                                p.getExpectedAmount(),
                                                p.getCurrency(),
                                                p.getConfirmedAmount(),
                                                p.getConfirmedCurrency(),
                                                p.isReconciliationRequired(),
                                                p.getReconciliationReason(),
                                                p.getInitiatedAt(),
                                                p.getConfirmedAt(),
                                                p.getLastCheckedAt()))
                        .toList(),
                at);
    }

    private static PaymentRequestSummaryView summary(
            PaymentRequest r, List<Payment> attempts, List<PaymentReview> reviews, Instant at) {
        var risks = PaymentRisk.reasons(r, attempts, reviews);
        var classification = PaymentDashboardPolicy.classify(r, risks, at);
        return new PaymentRequestSummaryView(
                r.getId(),
                r.getOrganizationId(),
                r.getOrderId(),
                r.getPurpose(),
                r.getBusinessReference(),
                r.getExpectedAmount(),
                r.getCurrency(),
                r.getGoodsAmount(),
                r.getFeeAmount(),
                r.getShippingAmount(),
                r.getAcceptanceStatus(),
                r.getExpiresAt(),
                r.getSettledPaymentId(),
                r.getCreatedAt(),
                r.getClosedAt(),
                r.getCancelledAt(),
                r.getVersion(),
                classification.status(),
                classification.reasons(),
                classification.terminalReason(),
                reviews.stream().filter(v -> v.getStatus() == PaymentReviewStatus.OPEN).count(),
                risks.contains("PAYMENT_IN_PROGRESS"),
                risks.contains("PAYMENT_RECONCILIATION_REQUIRED"),
                r.getTarget());
    }
}
