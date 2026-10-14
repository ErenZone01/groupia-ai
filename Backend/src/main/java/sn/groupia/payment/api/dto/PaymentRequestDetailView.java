package sn.groupia.payment.api.dto;

import java.time.Instant;
import java.util.List;

public record PaymentRequestDetailView(
        PaymentRequestSummaryView request,
        List<PaymentAttemptReadView> attempts,
        Instant evaluationAt) {}
