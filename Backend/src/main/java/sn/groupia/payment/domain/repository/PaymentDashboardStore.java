package sn.groupia.payment.domain.repository;

import java.time.Instant;
import java.util.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.PaymentDashboardSummaryView;
import sn.groupia.payment.domain.model.*;

public interface PaymentDashboardStore {
    Page list(
            UUID organizationId,
            PaymentOperationalStatus status,
            PaymentPurpose purpose,
            UUID orderId,
            int page,
            int size,
            Instant evaluationAt,
            boolean includeSubscriptions);

    default Page list(
            UUID org,
            PaymentOperationalStatus status,
            PaymentPurpose purpose,
            UUID order,
            int page,
            int size,
            Instant at) {
        return list(org, status, purpose, order, page, size, at, true);
    }

    Facts facts(List<UUID> requestIds);

    record Row(UUID id, PaymentOperationalStatus status) {}

    record Page(List<Row> rows, long total, PaymentDashboardSummaryView summary) {}

    record Facts(
            List<PaymentRequest> requests, List<Payment> attempts, List<PaymentReview> reviews) {}
}
