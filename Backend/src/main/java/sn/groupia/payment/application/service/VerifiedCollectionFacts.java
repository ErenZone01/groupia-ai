package sn.groupia.payment.application.service;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.payment.api.*;
import sn.groupia.payment.domain.repository.*;

@Service
@Transactional(readOnly = true)
public class VerifiedCollectionFacts implements CollectionFacts {
    private final PaymentOperations payments;
    private final FinancialStore store;
    private final PaymentQuoteStore quotes;
    private final JdbcTemplate jdbc;

    public VerifiedCollectionFacts(
            PaymentOperations p, FinancialStore s, PaymentQuoteStore q, JdbcTemplate j) {
        payments = p;
        store = s;
        quotes = q;
        jdbc = j;
    }

    public List<UUID> settledRequests(UUID org, UUID after, int limit) {
        if (limit < 1 || limit > 100)
            throw new IllegalArgumentException("Invalid collection page size");
        return jdbc.query(
                "select id from payment_requests where organization_id=? and acceptance_status='CLOSED' and settled_payment_id is not null and purpose in ('INITIAL_ORDER','SHIPPING_BALANCE','DIRECT_ORDER_INITIAL') and id>? order by id limit ?",
                (rs, n) -> rs.getObject("id", UUID.class),
                org,
                after == null ? new UUID(0, 0) : after,
                limit);
    }

    public Fact read(UUID org, UUID request) {
        var proof = payments.settlementEvidence(org, request);
        var r = payments.request(org, request);
        if (proof.purpose() == PaymentPurpose.SUBSCRIPTION_MONTHLY
                || proof.target().type() == PaymentTargetType.SUBSCRIPTION_INVOICE)
            throw new IllegalArgumentException("Subscriptions are never seller collections");
        var seller = r.goodsAmount().add(r.shippingAmount());
        if (seller.signum() < 0
                || r.feeAmount().signum() < 0
                || seller.add(r.feeAmount()).compareTo(proof.amount()) != 0)
            throw new IllegalStateException("Collection breakdown mismatch");
        if (!"SASPAY".equals(proof.provider()))
            return new Fact(proof, seller, r.feeAmount(), null, null);
        var p = store.payment(proof.paymentId()).orElseThrow();
        var q =
                quotes.find(p.getFeeQuoteId())
                        .orElseThrow(() -> new IllegalStateException("Missing settled quote"));
        if (!q.organizationId().equals(org)
                || !q.requestId().equals(request)
                || !q.provider().equals(proof.provider())
                || !Objects.equals(q.account(), proof.providerAccountReference())
                || !q.currency().equals(proof.currency())
                || q.amount().compareTo(proof.amount()) != 0
                || r.feeAmount().signum() != 0)
            throw new IllegalStateException("Settled quote scope mismatch");
        return new Fact(proof, seller, r.feeAmount(), q.fee(), q.amount().add(q.fee()));
    }
}
