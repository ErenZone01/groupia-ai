package sn.groupia.payment.domain.service;

import java.util.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.domain.model.*;

/** One classification for transport, fulfillment and completion. Never erases financial facts. */
public final class PaymentRisk {
    private PaymentRisk() {}

    public static Set<String> reasons(
            PaymentRequest r, List<Payment> attempts, List<PaymentReview> reviews) {
        var reasons = new LinkedHashSet<String>();
        if (reviews.stream().anyMatch(v -> v.getStatus() == PaymentReviewStatus.OPEN))
            reasons.add("PAYMENT_REVIEW_OPEN");
        for (var p : attempts) {
            if (p.isReconciliationRequired()) {
                reasons.add("PAYMENT_RECONCILIATION_REQUIRED");
                if ("PROVIDER_REVERSAL".equals(p.getReconciliationReason()))
                    reasons.add("PAYMENT_PROVIDER_REVERSAL");
                if ("PROVIDER_STATE_UNCERTAIN".equals(p.getReconciliationReason()))
                    reasons.add("PAYMENT_PROVIDER_STATE_UNCERTAIN");
                if ("TRANSACTION_MISMATCH".equals(p.getReconciliationReason()))
                    reasons.add("PAYMENT_TRANSACTION_INCONSISTENT");
            }
            if (p.getStatus() == PaymentStatus.CREATED || p.getStatus() == PaymentStatus.PENDING)
                reasons.add("PAYMENT_IN_PROGRESS");
            if (p.getStatus() != PaymentStatus.SUCCEEDED) continue;
            if (!consistent(r, p)) {
                reasons.add("PAYMENT_INCONSISTENT");
                continue;
            }
            if (p.getId().equals(r.getSettledPaymentId())) continue;
            boolean refunded =
                    reviews.stream()
                            .map(v -> v.view(r.getTarget()))
                            .anyMatch(
                                    v ->
                                            v.status().equals("RESOLVED_REFUNDED")
                                                    && v.reason().equals(PaymentReview.DUPLICATE)
                                                    && v.anomalousPaymentId().equals(p.getId())
                                                    && v.principalPaymentId()
                                                            .equals(r.getSettledPaymentId())
                                                    && v.anomalousProvider().equals(p.getProvider())
                                                    && Objects.equals(
                                                            v.anomalousProviderAccountReference(),
                                                            p.getProviderAccountReference())
                                                    && Objects.equals(
                                                            v.anomalousProviderTransactionId(),
                                                            p.getProviderTransactionId())
                                                    && v.observedAmount()
                                                                    .compareTo(
                                                                            p.getConfirmedAmount())
                                                            == 0
                                                    && v.observedCurrency()
                                                            .equals(p.getConfirmedCurrency()));
            if (!refunded) reasons.add("PAYMENT_UNALLOCATED_SUCCESS");
        }
        if (r.getSettledPaymentId() != null
                && (r.getAcceptanceStatus() != AcceptanceStatus.CLOSED
                        || attempts.stream()
                                .noneMatch(
                                        p ->
                                                p.getId().equals(r.getSettledPaymentId())
                                                        && p.getStatus() == PaymentStatus.SUCCEEDED
                                                        && consistent(r, p))))
            reasons.add("PAYMENT_ALLOCATION_INCONSISTENT");
        if (r.getAcceptanceStatus() == AcceptanceStatus.CLOSED && r.getSettledPaymentId() == null)
            reasons.add("PAYMENT_ALLOCATION_INCONSISTENT");
        return Collections.unmodifiableSet(reasons);
    }

    private static boolean consistent(PaymentRequest r, Payment p) {
        return r.getId().equals(p.getPaymentRequestId())
                && r.getOrganizationId().equals(p.getOrganizationId())
                && p.getProviderTransactionId() != null
                && !p.getProviderTransactionId().isBlank()
                && p.getExpectedAmount().compareTo(r.getExpectedAmount()) == 0
                && p.getCurrency().equals(r.getCurrency())
                && p.getConfirmedAmount() != null
                && p.getConfirmedAmount().compareTo(r.getExpectedAmount()) == 0
                && r.getCurrency().equals(p.getConfirmedCurrency());
    }
}
