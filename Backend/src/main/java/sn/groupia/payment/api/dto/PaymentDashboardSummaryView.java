package sn.groupia.payment.api.dto;

public record PaymentDashboardSummaryView(
        long pending, long settled, long reviewRequired, long terminalUnpaid, long openReviews) {}
