package sn.groupia.payment.domain.repository;

import java.util.*;
import sn.groupia.payment.domain.model.*;

public interface PaymentReviewStore {
    Optional<PaymentReview> byTransaction(String provider, String account, String transaction);

    Optional<PaymentReview> find(UUID organizationId, UUID id);

    PaymentReview lock(UUID organizationId, UUID id);

    void add(PaymentReview review);

    void flush();

    boolean hasOpen(UUID paymentId);

    List<PaymentReview> forRequest(UUID request);

    Page list(
            UUID organizationId,
            PaymentReviewStatus status,
            UUID paymentRequestId,
            int page,
            int size);

    record Page(List<PaymentReview> items, long total) {}
}
