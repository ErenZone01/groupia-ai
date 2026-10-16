package sn.groupia.subscription.infrastructure.persistence;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import sn.groupia.subscription.domain.model.SubscriptionInvoice;
import sn.groupia.subscription.domain.repository.SubscriptionInvoices;

@Repository
class JdbcSubscriptionInvoices implements SubscriptionInvoices {
    private final JdbcTemplate jdbc;

    JdbcSubscriptionInvoices(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SubscriptionInvoice> list(UUID org) {
        return jdbc.query(
                "select * from subscription_invoices where organization_id=? order by period_start desc,id",
                JdbcSubscriptionInvoices::map,
                org);
    }

    public Optional<SubscriptionInvoice> find(UUID org, UUID id) {
        return jdbc
                .query(
                        "select * from subscription_invoices where organization_id=? and id=?",
                        JdbcSubscriptionInvoices::map,
                        org,
                        id)
                .stream()
                .findFirst();
    }

    public Optional<SubscriptionInvoice> byCreationKey(UUID org, String key) {
        return jdbc
                .query(
                        "select * from subscription_invoices where organization_id=? and creation_key=?",
                        JdbcSubscriptionInvoices::map,
                        org,
                        key)
                .stream()
                .findFirst();
    }

    public Optional<SubscriptionInvoice> lock(UUID org, UUID id) {
        return jdbc
                .query(
                        "select * from subscription_invoices where organization_id=? and id=? for update",
                        JdbcSubscriptionInvoices::map,
                        org,
                        id)
                .stream()
                .findFirst();
    }

    public void insert(SubscriptionInvoice i) {
        jdbc.update(
                """
            insert into subscription_invoices(id,organization_id,subscription_id,billing_period_key,reference,plan_code,
                period_start,period_end,billing_timezone,amount,currency,billing_interval,billing_name,billing_phone_e164,
                billing_email,billing_country,billing_locale,payment_request_id,status,creation_key,created_at)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
                i.id(),
                i.organizationId(),
                i.subscriptionId(),
                i.billingPeriodKey(),
                i.reference(),
                i.planCode(),
                ts(i.periodStart()),
                ts(i.periodEnd()),
                i.billingTimezone(),
                i.amount(),
                i.currency(),
                i.billingInterval(),
                i.billingName(),
                i.billingPhoneE164(),
                i.billingEmail(),
                i.billingCountry(),
                i.billingLocale(),
                i.paymentRequestId(),
                i.status(),
                i.creationKey(),
                ts(i.createdAt()));
    }

    public void expire(UUID id, Instant at) {
        jdbc.update(
                "update subscription_invoices set status='EXPIRED',expired_at=?,version=version+1 where id=? and status='OPEN'",
                ts(at),
                id);
    }

    public void markPaid(UUID id, UUID payment, Instant at) {
        if (jdbc.update(
                        "update subscription_invoices set status='PAID',settled_payment_id=?,paid_at=?,version=version+1 where id=? and status='OPEN'",
                        payment,
                        ts(at),
                        id)
                != 1) throw new IllegalStateException("Invoice is not open");
    }

    private static Timestamp ts(Instant v) {
        return v == null ? null : Timestamp.from(v);
    }

    private static Instant instant(ResultSet r, String c) throws SQLException {
        var t = r.getTimestamp(c);
        return t == null ? null : t.toInstant();
    }

    private static SubscriptionInvoice map(ResultSet r, int row) throws SQLException {
        return new SubscriptionInvoice(
                r.getObject("id", UUID.class),
                r.getObject("organization_id", UUID.class),
                r.getObject("subscription_id", UUID.class),
                r.getString("billing_period_key"),
                r.getString("reference"),
                r.getString("plan_code"),
                instant(r, "period_start"),
                instant(r, "period_end"),
                r.getString("billing_timezone"),
                r.getBigDecimal("amount"),
                r.getString("currency"),
                r.getString("billing_interval"),
                r.getString("billing_name"),
                r.getString("billing_phone_e164"),
                r.getString("billing_email"),
                r.getString("billing_country"),
                r.getString("billing_locale"),
                r.getObject("payment_request_id", UUID.class),
                r.getString("status"),
                r.getObject("settled_payment_id", UUID.class),
                instant(r, "paid_at"),
                r.getString("creation_key"),
                instant(r, "created_at"),
                instant(r, "expired_at"),
                r.getLong("version"));
    }
}
