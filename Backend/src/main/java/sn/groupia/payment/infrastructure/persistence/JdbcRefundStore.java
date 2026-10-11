package sn.groupia.payment.infrastructure.persistence;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import sn.groupia.payment.api.dto.ReserveRefund;
import sn.groupia.payment.domain.model.Refund;
import sn.groupia.payment.domain.repository.RefundStore;
import sn.groupia.payment.domain.service.RefundObservation;
import sn.groupia.shared.api.ApiException;

@Repository
class JdbcRefundStore implements RefundStore {
    private final JdbcTemplate jdbc;
    private final tools.jackson.databind.ObjectMapper json;
    private final sn.groupia.payment.domain.repository.FinancialStore financial;

    JdbcRefundStore(
            JdbcTemplate jdbc,
            tools.jackson.databind.ObjectMapper json,
            sn.groupia.payment.domain.repository.FinancialStore financial) {
        this.jdbc = jdbc;
        this.json = json;
        this.financial = financial;
    }

    private Instant instant(ResultSet r, String name) throws SQLException {
        var v = r.getTimestamp(name);
        return v == null ? null : v.toInstant();
    }

    private final RowMapper<Refund> mapper =
            (r, n) ->
                    new Refund(
                            r.getObject("id", UUID.class),
                            r.getObject("organization_id", UUID.class),
                            r.getObject("order_id", UUID.class),
                            r.getObject("calculation_id", UUID.class),
                            r.getBigDecimal("amount"),
                            r.getString("currency"),
                            r.getString("method"),
                            r.getString("external_method"),
                            r.getString("status"),
                            r.getString("business_reference"),
                            r.getString("merchant_reference"),
                            r.getString("provider"),
                            r.getString("provider_account_reference"),
                            r.getString("provider_transaction_id"),
                            r.getObject("provider_idempotency_key", UUID.class),
                            r.getString("recipient_phone_snapshot"),
                            r.getString("recipient_name_snapshot"),
                            r.getObject("initiated_by", UUID.class),
                            instant(r, "initiated_at"),
                            instant(r, "provider_occurred_at"),
                            instant(r, "completed_at"),
                            r.getBoolean("reconciliation_required"),
                            r.getString("reconciliation_reason"),
                            r.getString("manual_reference"),
                            r.getString("note"),
                            r.getLong("version"));

    public Optional<Refund> forOrder(UUID org, UUID order) {
        return jdbc
                .query(
                        "select * from payment_refunds where organization_id=? and order_id=?",
                        mapper,
                        org,
                        order)
                .stream()
                .findFirst();
    }

    public Optional<Refund> byReference(String ref) {
        return jdbc
                .query("select * from payment_refunds where merchant_reference=?", mapper, ref)
                .stream()
                .findFirst();
    }

    public Optional<Refund> get(UUID id) {
        return jdbc.query("select * from payment_refunds where id=?", mapper, id).stream()
                .findFirst();
    }

    public Refund lock(UUID id) {
        return jdbc
                .query("select * from payment_refunds where id=? for update", mapper, id)
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new ApiException(
                                        404, "REFUND_NOT_FOUND", "Régularisation introuvable."));
    }

    public void coordinate(String key) {
        if (key.startsWith("transaction:"))
            financial.coordinate(
                    "transaction:BICTORYS:sandbox:" + key.substring("transaction:".length()));
        else
            jdbc.query(
                    "select pg_advisory_xact_lock(hashtextextended(?,0))",
                    (r, n) -> 0,
                    "refund:" + key);
    }

    public boolean isRefundTransaction(String provider, String account, String transaction) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists(select 1 from payment_refunds where provider=? and provider_account_reference=? and provider_transaction_id=?)",
                        Boolean.class,
                        provider,
                        account,
                        transaction));
    }

    public Refund create(ReserveRefund c, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
            insert into payment_refunds(id,organization_id,order_id,calculation_id,amount,currency,purpose,method,external_method,status,
              business_reference,merchant_reference,provider,provider_account_reference,provider_idempotency_key,
              recipient_phone_snapshot,recipient_name_snapshot,initiated_by,initiated_at,reconciliation_required,reconciliation_reason,manual_reference,note)
            values (?,?,?,?,?,?,'SHIPPING_CREDIT',?,?,'CREATED',?,?,?,?,?,?,?,?,?,true,'AWAITING_DISPATCH',?,?)
            """,
                id,
                c.organizationId(),
                c.orderId(),
                c.calculationId(),
                c.amount(),
                c.currency(),
                c.method(),
                c.externalMethod(),
                "SHIPPING_CREDIT:"
                        + c.orderId()
                        + ":"
                        + c.calculationId()
                        + ":"
                        + c.amount().toPlainString(),
                "GPR-" + id,
                c.method().equals("BICTORYS_PAYOUT") ? "BICTORYS" : "MANUAL_EXTERNAL",
                c.method().equals("BICTORYS_PAYOUT") ? "sandbox" : "internal",
                UUID.randomUUID(),
                c.recipientPhone(),
                c.recipientName(),
                c.actor(),
                Timestamp.from(now),
                c.reference(),
                c.note());
        return get(id).orElseThrow();
    }

    public void state(
            UUID id,
            String status,
            boolean review,
            String reason,
            String transaction,
            Instant providerAt,
            Instant completed) {
        jdbc.update(
                """
            update payment_refunds set status=?,reconciliation_required=?,reconciliation_reason=?,
            provider_transaction_id=coalesce(?,provider_transaction_id),provider_occurred_at=coalesce(?,provider_occurred_at),
            completed_at=coalesce(?,completed_at),version=version+1 where id=?
            """,
                status,
                review,
                reason,
                transaction,
                providerAt == null ? null : Timestamp.from(providerAt),
                completed == null ? null : Timestamp.from(completed),
                id);
    }

    public List<Refund> history(UUID org, UUID order) {
        return jdbc.query(
                "select * from payment_refunds where organization_id=? and order_id=? order by initiated_at,id",
                mapper,
                org,
                order);
    }

    public List<Refund> reviewQueue(UUID org) {
        return jdbc.query(
                "select * from payment_refunds where organization_id=? and (reconciliation_required or status in ('CREATED','PENDING','REVIEW_REQUIRED')) order by initiated_at,id limit 100",
                mapper,
                org);
    }

    public boolean transactionTaken(
            String provider, String account, String transaction, UUID except) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists(select 1 from payment_refunds where provider=? and provider_account_reference=? and provider_transaction_id=? and id<>?) or exists(select 1 from payments where provider=? and provider_account_reference=? and provider_transaction_id=?) or exists(select 1 from payment_reviews where anomalous_provider=? and anomalous_provider_account_reference is not distinct from ? and anomalous_provider_transaction_id=?)",
                        Boolean.class,
                        provider,
                        account,
                        transaction,
                        except,
                        provider,
                        account,
                        transaction,
                        provider,
                        account,
                        transaction));
    }

    public UUID receipt(
            String hash,
            boolean authentic,
            RefundObservation observation,
            String error,
            Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
            insert into refund_webhook_receipts(id,fingerprint,authentic,observation,processing_status,last_error_code,received_at)
            values (?,?,?,?::jsonb,'REVIEW_REQUIRED',?,?) on conflict(fingerprint,authentic) do nothing
            """,
                id,
                hash,
                authentic,
                observation == null ? null : json.writeValueAsString(observation),
                error,
                Timestamp.from(now));
        return jdbc.queryForObject(
                "select id from refund_webhook_receipts where fingerprint=? and authentic=?",
                UUID.class,
                hash,
                authentic);
    }

    public void receiptResult(UUID id, UUID refund, String status, String reason) {
        jdbc.update(
                "update refund_webhook_receipts set refund_id=?,processing_status=?,last_error_code=? where id=?",
                refund,
                status,
                reason,
                id);
    }
}
