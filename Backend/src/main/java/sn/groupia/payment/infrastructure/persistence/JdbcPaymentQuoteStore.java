package sn.groupia.payment.infrastructure.persistence;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import sn.groupia.payment.domain.model.PaymentFeeQuote;
import sn.groupia.payment.domain.repository.PaymentQuoteStore;

@Repository
class JdbcPaymentQuoteStore implements PaymentQuoteStore {
    private final JdbcTemplate jdbc;

    JdbcPaymentQuoteStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(PaymentFeeQuote q) {
        jdbc.update(
                """
                insert into payment_fee_quotes(id,organization_id,request_id,provider,account,
                country,network,label,amount,fee,currency,expires_at) values (?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                q.id(),
                q.organizationId(),
                q.requestId(),
                q.provider(),
                q.account(),
                q.country(),
                q.network(),
                q.label(),
                q.amount(),
                q.fee(),
                q.currency(),
                java.sql.Timestamp.from(q.expiresAt()));
    }

    public Optional<PaymentFeeQuote> find(UUID id) {
        if (id == null) return Optional.empty();
        return jdbc
                .query(
                        "select * from payment_fee_quotes where id=?",
                        (r, n) ->
                                new PaymentFeeQuote(
                                        r.getObject("id", UUID.class),
                                        r.getObject("organization_id", UUID.class),
                                        r.getObject("request_id", UUID.class),
                                        r.getString("provider"),
                                        r.getString("account"),
                                        r.getString("country"),
                                        r.getString("network"),
                                        r.getString("label"),
                                        r.getBigDecimal("amount"),
                                        r.getBigDecimal("fee"),
                                        r.getString("currency"),
                                        r.getTimestamp("expires_at").toInstant()),
                        id)
                .stream()
                .findFirst();
    }
}
