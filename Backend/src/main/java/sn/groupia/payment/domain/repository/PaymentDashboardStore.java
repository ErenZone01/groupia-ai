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
            Instant evaluationAt);

    Facts facts(List<UUID> requestIds);

    record Row(UUID id, PaymentOperationalStatus status) {}

    record Page(List<Row> rows, long total, PaymentDashboardSummaryView summary) {}

    record Facts(
            List<PaymentRequest> requests, List<Payment> attempts, List<PaymentReview> reviews) {}
}
