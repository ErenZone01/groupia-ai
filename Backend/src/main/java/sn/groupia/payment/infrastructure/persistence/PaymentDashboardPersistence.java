package sn.groupia.payment.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.PaymentDashboardSummaryView;
import sn.groupia.payment.domain.model.*;
import sn.groupia.payment.domain.repository.PaymentDashboardStore;

@Repository
class PaymentDashboardPersistence implements PaymentDashboardStore {
    private final EntityManager em;

    PaymentDashboardPersistence(EntityManager em) {
        this.em = em;
    }

    @Override
    public Page list(
            UUID org,
            PaymentOperationalStatus status,
            PaymentPurpose purpose,
            UUID orderId,
            int page,
            int size,
            Instant at,
            boolean includeSubscriptions) {
        String scope =
                (purpose == null ? "" : " and r.purpose=:purpose")
                        + (orderId == null ? "" : " and r.order_id=:orderId")
                        + (includeSubscriptions ? "" : " and r.target_type='ORDER'");
        String cte = PaymentDashboardSql.CLASSIFIED.formatted(scope);
        String filter = status == null ? "" : " where operational_status=:status";
        var query =
                bind(
                        em.createNativeQuery(
                                cte
                                        + "select id,operational_status from classified"
                                        + filter
                                        + " order by created_at desc,id"),
                        org,
                        purpose,
                        orderId,
                        at);
        if (status != null) query.setParameter("status", status.name());
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.setFirstResult(page * size).setMaxResults(size).getResultList();
        // The four category counts ignore category/page; incident badge covers the whole
        // organization.
        var count =
                bind(
                        em.createNativeQuery(
                                cte
                                        + """
            select count(*) filter(where operational_status='PENDING'),
                count(*) filter(where operational_status='SETTLED'),
                count(*) filter(where operational_status='REVIEW_REQUIRED'),
                count(*) filter(where operational_status='TERMINAL_UNPAID'),
                (select count(*) from payment_reviews v join payment_requests pr on pr.id=v.payment_request_id where v.organization_id=:org and v.status='OPEN' and (:includeSubscriptions or pr.target_type='ORDER'))
            from classified
            """),
                        org,
                        purpose,
                        orderId,
                        at);
        count.setParameter("includeSubscriptions", includeSubscriptions);
        Object[] counts = (Object[]) count.getSingleResult();
        long pending = n(counts[0]),
                settled = n(counts[1]),
                review = n(counts[2]),
                terminal = n(counts[3]);
        long total =
                status == null
                        ? pending + settled + review + terminal
                        : switch (status) {
                            case PENDING -> pending;
                            case SETTLED -> settled;
                            case REVIEW_REQUIRED -> review;
                            case TERMINAL_UNPAID -> terminal;
                        };
        return new Page(
                rows.stream()
                        .map(
                                r ->
                                        new Row(
                                                (UUID) r[0],
                                                PaymentOperationalStatus.valueOf((String) r[1])))
                        .toList(),
                total,
                new PaymentDashboardSummaryView(pending, settled, review, terminal, n(counts[4])));
    }

    private Query bind(Query q, UUID org, PaymentPurpose purpose, UUID orderId, Instant at) {
        q.setParameter("org", org).setParameter("at", at);
        if (purpose != null) q.setParameter("purpose", purpose.name());
        if (orderId != null) q.setParameter("orderId", orderId);
        return q;
    }

    private static long n(Object value) {
        return ((Number) value).longValue();
    }

    @Override
    public Facts facts(List<UUID> ids) {
        if (ids.isEmpty()) return new Facts(List.of(), List.of(), List.of());
        return new Facts(
                em.createQuery(
                                "select r from PaymentRequest r where r.id in :ids",
                                PaymentRequest.class)
                        .setParameter("ids", ids)
                        .getResultList(),
                em.createQuery(
                                "select p from Payment p where p.paymentRequestId in :ids order by p.initiatedAt,p.id",
                                Payment.class)
                        .setParameter("ids", ids)
                        .getResultList(),
                em.createQuery(
                                "select v from PaymentReview v where v.paymentRequestId in :ids",
                                PaymentReview.class)
                        .setParameter("ids", ids)
                        .getResultList());
    }
}
