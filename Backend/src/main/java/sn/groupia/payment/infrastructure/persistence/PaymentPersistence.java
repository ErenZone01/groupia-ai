package sn.groupia.payment.infrastructure.persistence;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import jakarta.persistence.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import sn.groupia.payment.api.PaymentPurpose;
import sn.groupia.payment.domain.model.*;
import sn.groupia.payment.domain.repository.FinancialStore;
import sn.groupia.shared.api.ApiException;

@Repository
class PaymentPersistence implements FinancialStore {
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    PaymentPersistence(EntityManager em, JdbcTemplate jdbc) { this.em = em; this.jdbc = jdbc; }
    @Override public void coordinate(String key) {
        try {
            long lock = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(("payment:"+key).getBytes(StandardCharsets.UTF_8))).getLong();
            jdbc.query("select pg_advisory_xact_lock(?)",(rs,row)->0,lock);
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    @Override public void add(PaymentRequest r) { em.persist(r); }
    @Override public void add(Payment p) { em.persist(p); }
    @Override public void add(PaymentWebhookEvent e) { em.persist(e); }
    @Override public void flush() { em.flush(); }
    @Override public Optional<PaymentRequest> byBusinessReference(String ref) {
        return em.createQuery("select r from PaymentRequest r where r.businessReference=:ref",PaymentRequest.class)
                .setParameter("ref",ref).getResultStream().findFirst();
    }
    @Override public Optional<PaymentRequest> initial(UUID org, UUID order) {
        return em.createQuery("select r from PaymentRequest r where r.organizationId=:org and r.orderId=:order and r.purpose=:purpose",PaymentRequest.class)
                .setParameter("org",org).setParameter("order",order).setParameter("purpose",PaymentPurpose.INITIAL_ORDER).getResultStream().findFirst();
    }
    @Override public Optional<PaymentRequest> request(UUID id) { return Optional.ofNullable(em.find(PaymentRequest.class,id)); }
    @Override public PaymentRequest lockRequest(UUID id) {
        var r = em.find(PaymentRequest.class,id,LockModeType.PESSIMISTIC_WRITE);
        if (r == null) { throw new ApiException(404,"PAYMENT_REQUEST_NOT_FOUND","Obligation introuvable."); }
        em.refresh(r,LockModeType.PESSIMISTIC_WRITE);
        return r;
    }
    @Override public Optional<Payment> attempt(UUID request, String key) {
        return em.createQuery("select p from Payment p where p.paymentRequestId=:r and p.initiationKey=:key",Payment.class)
                .setParameter("r",request).setParameter("key",key).getResultStream().findFirst();
    }
    @Override public List<Payment> attempts(UUID request) {
        return em.createQuery("select p from Payment p where p.paymentRequestId=:r order by p.initiatedAt,p.id",Payment.class)
                .setParameter("r",request).getResultList();
    }
    @Override public Optional<Payment> payment(UUID id) { return Optional.ofNullable(em.find(Payment.class,id)); }
    @Override public Optional<Payment> byMerchant(String ref) {
        return em.createQuery("select p from Payment p where p.merchantReference=:ref",Payment.class)
                .setParameter("ref",ref).getResultStream().findFirst();
    }
    @Override public Optional<Payment> byProviderTransaction(String provider,String account,String transaction) {
        return em.createQuery("select p from Payment p where p.provider=:provider and (p.providerAccountReference=:account or (p.providerAccountReference is null and :account is null)) and p.providerTransactionId=:tx",Payment.class)
                .setParameter("provider",provider).setParameter("account",account).setParameter("tx",transaction).getResultStream().findFirst();
    }
    @Override public Payment lockPayment(UUID id) {
        var p = em.find(Payment.class,id,LockModeType.PESSIMISTIC_WRITE);
        if (p == null) { throw new ApiException(404,"PAYMENT_NOT_FOUND","Tentative introuvable."); }
        em.refresh(p,LockModeType.PESSIMISTIC_WRITE);
        return p;
    }
    @Override public Optional<PaymentWebhookEvent> byWebhookKey(String key) {
        return em.createQuery("select e from PaymentWebhookEvent e where e.deduplicationKey=:key",PaymentWebhookEvent.class)
                .setParameter("key",key).getResultStream().findFirst();
    }
    @Override public PaymentWebhookEvent lockWebhook(UUID id) {
        var e = em.find(PaymentWebhookEvent.class,id,LockModeType.PESSIMISTIC_WRITE);
        if (e == null) { throw new ApiException(404,"PAYMENT_WEBHOOK_NOT_FOUND","Réception introuvable."); }
        return e;
    }
    @Override public Optional<PaymentWebhookEvent> webhook(UUID id) { return Optional.ofNullable(em.find(PaymentWebhookEvent.class,id)); }
    @Override public RequestPage list(UUID org,int page,int size) {
        var items = em.createQuery("select r from PaymentRequest r where r.organizationId=:org order by r.createdAt desc,r.id",PaymentRequest.class)
                .setParameter("org",org).setFirstResult(page*size).setMaxResults(size).getResultList();
        long total = em.createQuery("select count(r) from PaymentRequest r where r.organizationId=:org",Long.class).setParameter("org",org).getSingleResult();
        return new RequestPage(items,total);
    }
    @Override public List<UUID> retryableWebhooks(Instant now,int limit) {
        return em.createQuery("select e.id from PaymentWebhookEvent e where e.processingStatus in ('RECEIVED','RETRYABLE_FAILURE') and (e.nextAttemptAt is null or e.nextAttemptAt<=:now) order by e.receivedAt",UUID.class)
                .setParameter("now",now).setMaxResults(limit).getResultList();
    }
    @Override public List<UUID> uncertainPayments(int limit) {
        return em.createQuery("select p.id from Payment p where p.reconciliationRequired=true order by p.initiatedAt,p.id",UUID.class)
                .setMaxResults(limit).getResultList();
    }
}
