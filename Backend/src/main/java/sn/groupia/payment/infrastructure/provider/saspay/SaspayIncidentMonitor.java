package sn.groupia.payment.infrastructure.provider.saspay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.sql.Timestamp;
import org.springframework.dao.DataAccessException;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Durable operational monitoring only: all provider operations remain GET-only and all database
 * writes affect incident/checkpoint tables, never sellers' financial ledgers or transaction states.
 */
@Component
@ConditionalOnProperty(
        name = {
            "groupia.payment.saspay.enabled",
            "groupia.payment.saspay.history-audit.enabled",
            "groupia.payment.saspay.incident-monitor.enabled"
        },
        havingValue = "true")
final class SaspayIncidentMonitor {
    private static final Logger log = LoggerFactory.getLogger(SaspayIncidentMonitor.class);
    private static final Duration CHUNK = Duration.ofHours(6);
    private static final Duration PROVIDER_LAG = Duration.ofMinutes(5);
    private static final Duration LEASE = Duration.ofMinutes(30);
    private static final int MAX_WINDOWS = 4;
    private static final int LOCAL_SCAN_LIMIT = 250;

    private final JdbcTemplate jdbc;
    private final SaspayHistoryAudit history;
    private final String account;
    private final Clock clock;
    private final Instant configuredStart;

    SaspayIncidentMonitor(
            JdbcTemplate jdbc,
            SaspayHistoryAudit history,
            SaspayProperties properties,
            Clock clock,
            @Value("${groupia.payment.saspay.incident-monitor.start-at:}") String startAt) {
        this.jdbc = jdbc;
        this.history = history;
        this.account = properties.merchantId().toString();
        this.clock = clock;
        this.configuredStart = startAt == null || startAt.isBlank() ? null : Instant.parse(startAt);
    }

    @Scheduled(
            fixedDelayString = "${groupia.payment.saspay.history-audit.interval-ms:900000}",
            initialDelayString = "${groupia.payment.saspay.history-audit.initial-delay-ms:60000}")
    void scheduled() {
        try {
            runCycle();
        } catch (RuntimeException failure) {
            // Do not log provider payloads, phone numbers, raw identifiers or exception details.
            log.error("SasPay monitoring incomplete; audit checkpoint retained");
        }
    }

    void runCycle() {
        Instant now = clock.instant();
        UUID token = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO saspay_audit_checkpoints(provider_account_reference) VALUES (?) ON CONFLICT DO NOTHING",
                account);
        List<Instant> claims =
                jdbc.query(
                        """
                UPDATE saspay_audit_checkpoints
                SET lease_token=?, lease_until=?
                WHERE provider_account_reference=?
                    AND (lease_until IS NULL OR lease_until < ?)
                RETURNING completed_until
                """,
                        (rs, index) -> {
                            Timestamp ts = rs.getTimestamp(1);
                            return ts == null ? null : ts.toInstant();
                        },
                        token,
                        Timestamp.from(now.plus(LEASE)),
                        account,
                        Timestamp.from(now));
        if (claims.isEmpty()) return; // Another replica has the lease.
        Instant from =
                claims.getFirst() == null ? bootstrap(now, configuredStart) : claims.getFirst();
        String stage = "LOCAL_SCAN";
        try {
            scanLocal(now);
            Instant target = now.minus(PROVIDER_LAG);
            if (from.isAfter(target)) {
                // Operator-supplied bootstrap date cannot silently skip future periods.
                if (claims.getFirst() == null) {
                    recordIncident("AUDIT_START_IN_FUTURE", "configured-start");
                    throw new IllegalStateException(
                            "Audit start is later than stable provider horizon");
                }
                return;
            }
            int windows = 0;
            while (from.isBefore(target) && windows++ < MAX_WINDOWS) {
                Instant until = nextBoundary(from, target);
                stage = "HISTORY_READ";
                SaspayHistoryAudit.Result result =
                        history.compare(from, until, this::recordIncident);
                stage = "CHECKPOINT_WRITE";
                int advanced =
                        jdbc.update(
                                """
                        UPDATE saspay_audit_checkpoints
                        SET completed_until=?, last_success_at=?, last_failure_code=NULL
                        WHERE provider_account_reference=? AND lease_token=? AND lease_until > ?
                        """,
                                Timestamp.from(until),
                                Timestamp.from(clock.instant()),
                                account,
                                token,
                                Timestamp.from(clock.instant()));
                if (advanced != 1) throw new IllegalStateException("SasPay monitor lost its lease");
                log.info(
                        "SasPay monitored interval transactions={} unmatched={} differences={}",
                        result.transactions(),
                        result.unmatched(),
                        result.differences());
                from = until;
            }
            if (!from.isBefore(target)) {
                // Re-read recent operations: a transaction may become terminal after its
                // original created_at interval was checkpointed, without a new transaction.
                stage = "RECENT_RECHECK";
                history.compare(target.minus(Duration.ofHours(48)), target, this::recordIncident);
            }
        } catch (RuntimeException failure) {
            // Never advance a failed window. Alerting is best-effort; failure stays visible in DB.
            String failureCode = failureCode(stage, failure);
            jdbc.update(
                    """
                    UPDATE saspay_audit_checkpoints SET last_failure_code=?
                    WHERE provider_account_reference=? AND lease_token=?
                    """,
                    failureCode,
                    account,
                    token);
            log.error(
                    "SasPay monitor failed stage={} family={} sqlState={} providerHttpStatus={}; checkpoint retained",
                    stage,
                    failure instanceof DataAccessException ? "DATABASE"
                            : failure instanceof SaspayException ? "PROVIDER" : "INTERNAL",
                    safeSqlState(failure),
                    failure instanceof SaspayException ex ? ex.httpStatus() : 0);
            try {
                // A failed bootstrap used to hash a moving timestamp on every cycle,
                // producing a new incident every 15 minutes. One incident per account
                // now accumulates observations, regardless of retry or bootstrap time.
                recordIncident("AUDIT_INCOMPLETE", "MONITOR_FAILED");
            } catch (RuntimeException storeFailure) {
                log.error("SasPay incident persistence unavailable; checkpoint not advanced");
            }
            throw failure;
        } finally {
            jdbc.update(
                    """
                    UPDATE saspay_audit_checkpoints SET lease_token=NULL, lease_until=NULL
                    WHERE provider_account_reference=? AND lease_token=?
                    """,
                    account,
                    token);
        }
    }

    static Instant bootstrap(Instant now, Instant configuredStart) {
        return configuredStart == null ? now.minus(Duration.ofHours(48)) : configuredStart;
    }

    static Instant nextBoundary(Instant from, Instant target) {
        if (!from.isBefore(target)) throw new IllegalArgumentException("Empty audit window");
        Instant next = from.plus(CHUNK);
        return next.isBefore(target) ? next : target;
    }

    private void scanLocal(Instant now) {
        // Existing retry workers retain sole authority to verify and update transactions.
        scan(
                """
                SELECT id::text FROM payment_webhook_events
                WHERE provider='SASPAY' AND provider_account_reference=?
                  AND authenticity_verification_result='VALID'
                  AND (
                    processing_status='REVIEW_REQUIRED'
                    OR (processing_status='RECEIVED' AND received_at < ?)
                    OR (processing_status='RETRYABLE_FAILURE'
                        AND (next_attempt_at IS NULL OR next_attempt_at < ?))
                  )
                ORDER BY received_at, id LIMIT 251
                """,
                "WEBHOOK_STUCK",
                now.minus(Duration.ofMinutes(15)),
                now.minus(Duration.ofMinutes(15)));
        scan(
                """
                SELECT id::text FROM payments
                WHERE provider='SASPAY' AND provider_account_reference=?
                  AND (reconciliation_required
                    OR (status IN ('CREATED','PENDING') AND initiated_at < ?))
                ORDER BY initiated_at, id LIMIT 251
                """,
                "PAYMENT_REVIEW_PENDING",
                now.minus(Duration.ofMinutes(60)));
        scan(
                """
                SELECT id::text FROM merchant_payouts
                WHERE provider='SASPAY' AND provider_account_reference=?
                  AND (reconciliation_required OR status='REVIEW_REQUIRED'
                    OR (status IN ('CREATED','PENDING') AND requested_at < ?))
                ORDER BY requested_at, id LIMIT 251
                """,
                "PAYOUT_REVIEW_PENDING",
                now.minus(Duration.ofMinutes(60)));
        scan(
                """
                SELECT v.payout_id::text
                FROM merchant_payout_verifications v
                JOIN merchant_payouts p ON p.id=v.payout_id
                WHERE p.provider='SASPAY' AND p.provider_account_reference=?
                  AND v.next_verification_at < ?
                ORDER BY v.next_verification_at, v.payout_id LIMIT 251
                """,
                "PAYOUT_VERIFICATION_OVERDUE",
                now.minus(Duration.ofMinutes(30)));
    }

    private void scan(String sql, String issueCode, Object... filters) {
        Object[] args = new Object[filters.length + 1];
        args[0] = account;
        for (int i = 0; i < filters.length; i++) {
            // PostgreSQL JDBC maps java.sql.Timestamp to TIMESTAMPTZ; it cannot
            // infer the JDBC type for a raw java.time.Instant in this query.
            args[i + 1] =
                    filters[i] instanceof Instant instant ? Timestamp.from(instant) : filters[i];
        }
        List<String> rows = jdbc.query(sql, (rs, index) -> rs.getString(1), args);
        for (int i = 0; i < Math.min(rows.size(), LOCAL_SCAN_LIMIT); i++)
            recordIncident(issueCode, rows.get(i));
        if (rows.size() > LOCAL_SCAN_LIMIT) recordIncident("MONITORING_SCAN_OVERFLOW", issueCode);
    }

    static String failureCode(String stage, RuntimeException failure) {
        String family =
                failure instanceof DataAccessException ? "DATABASE"
                        : failure instanceof SaspayException ? "PROVIDER" : "INTERNAL";
        return family + "_" + stage;
    }

    static String safeSqlState(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; depth < 6 && cause != null; depth++, cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                return state != null && state.matches("[A-Za-z0-9]{5}") ? state : "NONE";
            }
        }
        return "NONE";
    }

    void recordIncident(String code, String rawReference) {
        if (code == null || !code.matches("[A-Z_]{3,64}"))
            throw new IllegalArgumentException("Unknown incident classification");
        // Only a one-way fingerprint persists; supplier identifiers and PII never enter logs.
        String fingerprint = fingerprint(rawReference);
        Instant now = clock.instant();
        jdbc.update(
                """
                INSERT INTO saspay_audit_incidents
                  (id, provider_account_reference, incident_code, reference_fingerprint,
                   status, first_seen_at, last_seen_at, occurrence_count)
                VALUES (?, ?, ?, ?, 'OPEN', ?, ?, 1)
                ON CONFLICT (provider_account_reference, incident_code, reference_fingerprint)
                DO UPDATE SET
                   last_seen_at=GREATEST(saspay_audit_incidents.last_seen_at, EXCLUDED.last_seen_at),
                   occurrence_count=saspay_audit_incidents.occurrence_count + 1
                """,
                UUID.randomUUID(),
                account,
                code,
                fingerprint,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    static String fingerprint(String raw) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    Objects.requireNonNullElse(raw, "missing")
                                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }
}
