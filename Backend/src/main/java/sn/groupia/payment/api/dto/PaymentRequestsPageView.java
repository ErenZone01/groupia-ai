package sn.groupia.payment.api.dto;

import java.time.Instant;
import java.util.List;

public record PaymentRequestsPageView(
        List<PaymentRequestSummaryView> items,
        int page,
        int size,
        long totalElements,
        long totalPages,
        PaymentDashboardSummaryView summary,
        Instant evaluationAt) {}
