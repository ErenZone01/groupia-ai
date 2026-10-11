package sn.groupia.payment.api.dto;

import java.util.List;

public record PaymentReviewPage(
        List<PaymentReviewView> items, int page, int size, long totalElements, long totalPages) {}
