package sn.groupia.payment.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.payment.api.OutgoingPayoutOperations.*;
import sn.groupia.payment.api.event.OutgoingPayoutEvidenceReceived;
import tools.jackson.databind.ObjectMapper;

@Repository
@Transactional
public class OutgoingPayoutStore {
    public record Stored(Registration registration, String transactionId) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public OutgoingPayoutStore(
            JdbcTemplate jdbc, ObjectMapper json, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
        this.clock = clock;
    }

    private void lock(String key) {
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(?,0))",
                (r, n) -> 0,
                "outgoing:" + key);
    }

    public void register(Registration r) {
        lock(r.operationType() + ":" + r.operationId());
        var existing = find(r.operationType(), r.operationId());
        if (existing.isPresent()) {
            var old = existing.get().registration();
            if (!old.equals(r)
                    && !(old.operationType() == r.operationType()
                            && old.operationId().equals(r.operationId())
                            && old.organizationId().equals(r.organizationId())
                            && old.provider().equals(r.provider())
                            && old.account().equals(r.account())
                            && old.businessReference().equals(r.businessReference())
                            && old.idempotencyKey().equals(r.idempotencyKey())
                            && old.amount().compareTo(r.amount()) == 0
                            && old.currency().equals(r.currency())
                            && old.method().equals(r.method())
                            && old.createdAt().equals(r.createdAt())))
                throw new IllegalStateException("PAYOUT_REGISTRATION_CONFLICT");
            return;
        }
        jdbc.update(
                """
            insert into outgoing_provider_operations(operation_type,operation_id,organization_id,provider,
            provider_account_reference,business_reference,provider_idempotency_key,expected_amount,currency,method,created_at)
            values (?,?,?,?,?,?,?,?,?,?,?)
            """,
                r.operationType().name(),
                r.operationId(),
                r.organizationId(),
                r.provider(),
                r.account(),
                r.businessReference(),
                r.idempotencyKey(),
                r.amount(),
                r.currency(),
                r.method(),
                Timestamp.from(r.createdAt()));
    }

    public Optional<Stored> find(Kind kind, UUID id) {
        return query("operation_type=? and operation_id=?", kind.name(), id);
    }

    public Optional<Stored> reference(String ref) {
        return query(
                "provider='BICTORYS' and provider_account_reference='sandbox' and business_reference=?",
                ref);
    }

    public Optional<Stored> transaction(String id) {
        return query(
                "provider='BICTORYS' and provider_account_reference='sandbox' and provider_transaction_id=?",
                id);
    }

    private Optional<Stored> query(String predicate, Object... args) {
        return jdbc
                .query(
                        "select * from outgoing_provider_operations where " + predicate,
                        (r, n) ->
                                new Stored(
                                        new Registration(
                                                Kind.valueOf(r.getString("operation_type")),
                                                r.getObject("operation_id", UUID.class),
                                                r.getObject("organization_id", UUID.class),
                                                r.getString("provider"),
                                                r.getString("provider_account_reference"),
                                                r.getString("business_reference"),
                                                r.getObject("provider_idempotency_key", UUID.class),
                                                r.getBigDecimal("expected_amount"),
                                                r.getString("currency"),
                                                r.getString("method"),
                                                r.getTimestamp("created_at").toInstant()),
                                        r.getString("provider_transaction_id")),
                        args)
                .stream()
                .findFirst();
    }

    public String bind(Kind kind, UUID id, String transaction) {
        if (transaction == null || !transaction.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}"))
            return "PAYOUT_TRANSACTION_MISMATCH";
        // Common lock namespace for BOTH outgoing families, then serialize the operation itself.
        var identity = find(kind, id).orElseThrow().registration();
        lock("transaction:" + identity.provider() + ":" + identity.account() + ":" + transaction);
        lock(kind + ":" + id);
        var current = find(kind, id).orElseThrow();
        var owner =
                query(
                        "provider=? and provider_account_reference=? and provider_transaction_id=?",
                        identity.provider(),
                        identity.account(),
                        transaction);
        if (owner.isPresent()
                && (owner.get().registration().operationType() != kind
                        || !owner.get().registration().operationId().equals(id)))
            return "PAYOUT_TRANSACTION_ALREADY_USED";
        if (current.transactionId() != null && !current.transactionId().equals(transaction))
            return "PAYOUT_TRANSACTION_MISMATCH";
        jdbc.update(
                "update outgoing_provider_operations set provider_transaction_id=? where operation_type=? and operation_id=?",
                transaction,
                kind.name(),
                id);
        return null;
    }

    public UUID receipt(
            String fingerprint, boolean authentic, Stored owner, Evidence proof, String error) {
        UUID id = UUID.randomUUID();
        Registration r = owner == null ? null : owner.registration();
        Evidence e =
                proof == null
                        ? null
                        : new Evidence(
                                id,
                                proof.operationType(),
                                proof.operationId(),
                                proof.provider(),
                                proof.account(),
                                proof.businessReference(),
                                proof.transactionId(),
                                proof.amount(),
                                proof.currency(),
                                proof.method(),
                                proof.recipientPhone(),
                                proof.status(),
                                proof.occurredAt(),
                                proof.providerFee(),
                                authentic,
                                error == null ? proof.errorCode() : error);
        int inserted =
                jdbc.update(
                        """
            insert into refund_webhook_receipts(id,fingerprint,authentic,provider,provider_account_reference,operation_type,operation_id,
            provider_transaction_id,business_reference,normalized_evidence,processing_status,last_error_code,received_at)
            values (?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?) on conflict(fingerprint,authentic) do nothing
            """,
                        id,
                        fingerprint,
                        authentic,
                        r == null ? "BICTORYS" : r.provider(),
                        r == null ? "sandbox" : r.account(),
                        r == null ? null : r.operationType().name(),
                        r == null ? null : r.operationId(),
                        e == null
                                ? (owner == null ? null : owner.transactionId())
                                : e.transactionId(),
                        r == null ? null : r.businessReference(),
                        e == null ? null : json.writeValueAsString(e),
                        authentic && r != null && e != null ? "PENDING" : "REVIEW_REQUIRED",
                        error,
                        Timestamp.from(clock.instant()));
        if (inserted == 0)
            return jdbc.queryForObject(
                    "select id from refund_webhook_receipts where fingerprint=? and authentic=?",
                    UUID.class,
                    fingerprint,
                    authentic);
        if (authentic && r != null && e != null)
            events.publishEvent(
                    new OutgoingPayoutEvidenceReceived(
                            UUID.randomUUID(),
                            clock.instant(),
                            id,
                            r.operationType(),
                            r.operationId(),
                            r.organizationId()));
        return id;
    }

    public Evidence evidence(UUID id) {
        String data =
                jdbc.queryForObject(
                        "select normalized_evidence::text from refund_webhook_receipts where id=?",
                        String.class,
                        id);
        return data == null ? null : json.readValue(data, Evidence.class);
    }

    public void acknowledge(UUID id, String error) {
        // Once an incident is recorded, a replay cannot erase it.
        jdbc.update(
                """
            update refund_webhook_receipts set processing_status=?,last_error_code=?,processed_at=?
            where id=? and (processing_status='PENDING' or (processing_status='PROCESSED' and cast(? as text) is not null))
            """,
                error == null ? "PROCESSED" : "REVIEW_REQUIRED",
                error,
                Timestamp.from(clock.instant()),
                id,
                error);
    }
}
