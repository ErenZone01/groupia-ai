package sn.groupia.payment.infrastructure.persistence;

import jakarta.persistence.*;
import java.util.*;
import org.springframework.stereotype.Repository;
import sn.groupia.payment.domain.model.*;
import sn.groupia.payment.domain.repository.PaymentReviewStore;
import sn.groupia.shared.api.ApiException;

@Repository
class PaymentReviewPersistence implements PaymentReviewStore {
    private final EntityManager em;

    PaymentReviewPersistence(EntityManager em) {
        this.em = em;
    }

    public Optional<PaymentReview> byTransaction(
            String provider, String account, String transaction) {
        return em.createQuery(
                        "select r from PaymentReview r where r.anomalousProvider=:p and "
                                + "(r.anomalousProviderAccountReference=:a or (r.anomalousProviderAccountReference is null and :a is null)) "
                                + "and r.anomalousProviderTransactionId=:t",
                        PaymentReview.class)
                .setParameter("p", provider)
                .setParameter("a", account)
                .setParameter("t", transaction)
                .getResultStream()
                .findFirst();
    }

    public Optional<PaymentReview> find(UUID org, UUID id) {
        return em.createQuery(
                        "select r from PaymentReview r where r.organizationId=:org and r.id=:id",
                        PaymentReview.class)
                .setParameter("org", org)
                .setParameter("id", id)
                .getResultStream()
                .findFirst();
    }

    public PaymentReview lock(UUID org, UUID id) {
        var review =
                find(org, id)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                404,
                                                "PAYMENT_REVIEW_NOT_FOUND",
                                                "Anomalie introuvable."));
        em.refresh(review, LockModeType.PESSIMISTIC_WRITE);
        return review;
    }

    public void add(PaymentReview review) {
        em.persist(review);
    }

    public void flush() {
        em.flush();
    }

    public List<PaymentReview> forRequest(UUID request) {
        return em.createQuery(
                        "select r from PaymentReview r where r.paymentRequestId=:id",
                        PaymentReview.class)
                .setParameter("id", request)
                .getResultList();
    }

    public boolean hasOpen(UUID paymentId) {
        return em.createQuery(
                                "select count(r) from PaymentReview r where r.anomalousPaymentId=:p and r.status=:s",
                                Long.class)
                        .setParameter("p", paymentId)
                        .setParameter("s", PaymentReviewStatus.OPEN)
                        .getSingleResult()
                > 0;
    }

    public Page list(
            UUID org,
            PaymentReviewStatus status,
            UUID paymentRequestId,
            int page,
            int size,
            boolean includeSubscriptions) {
        String filter =
                " where r.organizationId=:org"
                        + (status == null ? "" : " and r.status=:status")
                        + (paymentRequestId == null ? "" : " and r.paymentRequestId=:requestId")
                        + (includeSubscriptions
                                ? ""
                                : " and exists (select p.id from PaymentRequest p where p.id=r.paymentRequestId and p.targetType=sn.groupia.payment.api.PaymentTargetType.ORDER)");
        var query =
                em.createQuery(
                                "select r from PaymentReview r"
                                        + filter
                                        + " order by r.detectedAt desc,r.id",
                                PaymentReview.class)
                        .setParameter("org", org);
        var count =
                em.createQuery("select count(r) from PaymentReview r" + filter, Long.class)
                        .setParameter("org", org);
        if (status != null) {
            query.setParameter("status", status);
            count.setParameter("status", status);
        }
        if (paymentRequestId != null) {
            query.setParameter("requestId", paymentRequestId);
            count.setParameter("requestId", paymentRequestId);
        }
        return new Page(
                query.setFirstResult(page * size).setMaxResults(size).getResultList(),
                count.getSingleResult());
    }
}
