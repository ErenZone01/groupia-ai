package sn.groupia.payment.api.dto;

import java.util.*;

public record OrderPaymentCompletionFacts(List<Obligation> obligations, List<RefundView> refunds) {
    public record Obligation(
            PaymentRequestView request, Set<String> risks, boolean pendingReceipt) {}
}
