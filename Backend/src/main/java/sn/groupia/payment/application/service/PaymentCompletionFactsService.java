package sn.groupia.payment.application.service;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.domain.repository.*;
import sn.groupia.payment.domain.service.PaymentRisk;

@Service
@Transactional(readOnly = true)
public class PaymentCompletionFactsService implements PaymentCompletionFacts {
    private final FinancialStore store;
    private final PaymentReviewStore reviews;
    private final RefundStore refunds;

    public PaymentCompletionFactsService(
            FinancialStore store, PaymentReviewStore reviews, RefundStore refunds) {
        this.store = store;
        this.reviews = reviews;
        this.refunds = refunds;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID org, List<UUID> ids) {
        for (var id : ids)
            for (var request : store.allRequests(org, id)) store.lockRequest(request.getId());
        for (var id : ids)
            refunds.history(org, id).stream()
                    .sorted(Comparator.comparing(r -> r.id().toString()))
                    .forEach(r -> refunds.lock(r.id()));
    }

    public OrderPaymentCompletionFacts read(UUID org, UUID order) {
        return new OrderPaymentCompletionFacts(
                store.allRequests(org, order).stream()
                        .map(
                                r ->
                                        new OrderPaymentCompletionFacts.Obligation(
                                                r.view(),
                                                PaymentRisk.reasons(
                                                        r,
                                                        store.attempts(r.getId()),
                                                        reviews.forRequest(r.getId())),
                                                store.pendingReceipts(r.getId())))
                        .toList(),
                refunds.history(org, order).stream().map(r -> r.view()).toList());
    }
}
