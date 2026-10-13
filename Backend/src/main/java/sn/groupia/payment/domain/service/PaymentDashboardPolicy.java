package sn.groupia.payment.domain.service;

import java.time.Instant;
import java.util.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.domain.model.PaymentRequest;

/** Presentation semantics only. Financial anomalies are supplied by PaymentRisk. */
public final class PaymentDashboardPolicy {
    private PaymentDashboardPolicy() {}

    public record Classification(
            PaymentOperationalStatus status, Set<String> reasons, String terminalReason) {}

    public static Classification classify(
            PaymentRequest request, Set<String> risks, Instant evaluationAt) {
        var reasons = new LinkedHashSet<>(risks);
        boolean expired =
                request.getExpiresAt() != null && !request.getExpiresAt().isAfter(evaluationAt);
        boolean payable =
                request.getAcceptanceStatus() == AcceptanceStatus.OPEN
                        && request.getSettledPaymentId() == null
                        && request.getExpectedAmount().signum() > 0
                        && !expired;
        if (request.getAcceptanceStatus() == AcceptanceStatus.OPEN
                && request.getExpectedAmount().signum() == 0)
            reasons.add("PAYMENT_ZERO_AMOUNT_REQUIRES_REVIEW");
        boolean anomaly = reasons.stream().anyMatch(r -> !r.equals("PAYMENT_IN_PROGRESS"));
        if (anomaly || (reasons.contains("PAYMENT_IN_PROGRESS") && !payable))
            return result(PaymentOperationalStatus.REVIEW_REQUIRED, reasons, null);
        if (request.getAcceptanceStatus() == AcceptanceStatus.CLOSED
                && request.getSettledPaymentId() != null)
            return result(PaymentOperationalStatus.SETTLED, reasons, null);
        if (payable) return result(PaymentOperationalStatus.PENDING, reasons, null);
        if (request.getSettledPaymentId() == null) {
            String terminal =
                    switch (request.getAcceptanceStatus()) {
                        case CANCELLED -> "CANCELLED";
                        case SUPERSEDED -> "SUPERSEDED";
                        case OPEN -> expired ? "DEADLINE_EXCEEDED" : null;
                        default -> null;
                    };
            if (terminal != null)
                return result(PaymentOperationalStatus.TERMINAL_UNPAID, reasons, terminal);
        }
        reasons.add("PAYMENT_CLASSIFICATION_UNCERTAIN");
        return result(PaymentOperationalStatus.REVIEW_REQUIRED, reasons, null);
    }

    private static Classification result(
            PaymentOperationalStatus status, Set<String> reasons, String terminal) {
        return new Classification(
                status, Collections.unmodifiableSet(new LinkedHashSet<>(reasons)), terminal);
    }
}
