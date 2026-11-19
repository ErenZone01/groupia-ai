package sn.groupia.payment.infrastructure.provider.saspay;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.shared.api.PlatformCurrentActor;
import sn.groupia.shared.api.ApiException;

/**
 * Platform-only financial incident case management. All decisions are append-only administrative
 * observations; no provider call or mutation of payment, payout or seller balances is possible
 * here.
 */
@Service
public class SaspayIncidentConsoleService {
    private final JdbcTemplate jdbc;
    private final PlatformCurrentActor currentUser;
    private final Clock clock;
    private final String account;
    private final String operators;
    private final boolean enabled;

    public SaspayIncidentConsoleService(
            JdbcTemplate jdbc,
            PlatformCurrentActor currentUser,
            SaspayProperties properties,
            Clock clock,
            @Value("${groupia.payment.saspay.incident-console.enabled:false}") boolean enabled,
            @Value("${groupia.payment.saspay.incident-console.operator-ids:}") String operators) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.clock = clock;
        this.account = properties.merchantId() == null ? null : properties.merchantId().toString();
        this.enabled = enabled && properties.enabled() && account != null;
        this.operators = operators;
    }

    public record Access(boolean allowed) {}

    public record Checkpoint(
            Instant completedUntil, Instant lastSuccessAt, String lastFailureCode) {}

    public record Incident(
            UUID id,
            String code,
            String referenceHint,
            String status,
            Instant firstSeenAt,
            Instant lastSeenAt,
            long occurrenceCount,
            Instant lastDecisionAt) {}

    public record Decision(
            UUID id,
            String action,
            UUID actorId,
            String evidenceReference,
            String reason,
            long observedOccurrenceCount,
            Instant recordedAt) {}

    public record IncidentDetail(Incident incident, List<Decision> decisions) {}

    public record Page(
            List<Incident> items,
            long total,
            int page,
            int size,
            long open,
            long inReview,
            long closed,
            Checkpoint checkpoint) {}

    public record CommandResult(UUID incidentId, UUID decisionId, String status) {}

    // A logged-in nonoperator can learn only their own access flag; never disclose incident data.
    public Access access() {
        UUID actor = currentUser.requireActiveUserId();
        return new Access(authorized(actor));
    }

    private UUID operator() {
        UUID actor = currentUser.requireActiveUserId();
        if (!authorized(actor))
            throw new ApiException(
                    403,
                    "SASPAY_INCIDENT_ADMIN_FORBIDDEN",
                    "Réservé aux opérateurs financiers autorisés.");
        return actor;
    }

    private boolean authorized(UUID actor) {
        return enabled
                && actor != null
                && operators != null
                && Arrays.stream(operators.split(","))
                        .map(String::strip)
                        .anyMatch(actor.toString()::equals);
    }

    private static String stateExpression() {
        return """
                CASE WHEN d.id IS NULL OR d.observed_occurrence_count <> i.occurrence_count
                    THEN 'OPEN'
                    WHEN d.action='CLOSED' THEN 'CLOSED'
                    ELSE 'IN_REVIEW'
                END
                """;
    }

    private static String incidentSelect() {
        return """
                SELECT i.id, i.incident_code, substring(i.reference_fingerprint,1,12) AS reference_hint,
                       i.first_seen_at,i.last_seen_at,i.occurrence_count,
                       d.recorded_at AS last_decision_at,
                """
                + stateExpression()
                + """
                        AS case_status
                FROM saspay_audit_incidents i
                LEFT JOIN LATERAL (
                  SELECT id, action, observed_occurrence_count, recorded_at
                  FROM saspay_audit_incident_decisions
                  WHERE incident_id=i.id
                  ORDER BY decision_number DESC LIMIT 1
                ) d ON true
                WHERE i.provider_account_reference=?
                """;
    }

    private static Incident incident(ResultSet rs, int index) throws SQLException {
        return new Incident(
                rs.getObject("id", UUID.class),
                rs.getString("incident_code"),
                rs.getString("reference_hint"),
                rs.getString("case_status"),
                rs.getTimestamp("first_seen_at").toInstant(),
                rs.getTimestamp("last_seen_at").toInstant(),
                rs.getLong("occurrence_count"),
                nullableInstant(rs, "last_decision_at"));
    }

    private static Instant nullableInstant(ResultSet rs, String name) throws SQLException {
        Timestamp raw = rs.getTimestamp(name);
        return raw == null ? null : raw.toInstant();
    }

    @Transactional(readOnly = true)
    public Page list(String status, String code, int page, int size) {
        operator();
        if (page < 0
                || page > 100000
                || size < 1
                || size > 50
                || (status != null
                        && !status.isBlank()
                        && !Set.of("OPEN", "IN_REVIEW", "CLOSED").contains(status))
                || (code != null && !code.isBlank() && !code.matches("[A-Z_]{3,64}")))
            throw new ApiException(400, "SASPAY_INCIDENT_FILTER_INVALID", "Filtres incorrects.");
        status = status == null || status.isBlank() ? null : status;
        code = code == null || code.isBlank() ? null : code;
        // All server-owned predicates are parameterized; user input never alters SQL syntax.
        String query =
                "SELECT * FROM ("
                        + incidentSelect()
                        + ") cases"
                        + " WHERE (CAST(? AS text) IS NULL OR case_status=?)"
                        + " AND (CAST(? AS text) IS NULL OR incident_code=?)";
        Object[] args = {account, status, status, code, code};
        long total =
                jdbc.queryForObject(
                        "SELECT count(*) FROM (" + query + ") filtered", Long.class, args);
        List<Incident> rows =
                jdbc.query(
                        query + " ORDER BY last_seen_at DESC,id DESC LIMIT ? OFFSET ?",
                        SaspayIncidentConsoleService::incident,
                        account,
                        status,
                        status,
                        code,
                        code,
                        size,
                        page * size);
        // Global statuses ignore the page filters; the numbers are operational dashboard totals.
        String summary =
                "SELECT case_status, count(*) AS total FROM ("
                        + incidentSelect()
                        + ") cases GROUP BY case_status";
        Map<String, Long> counts = new HashMap<>();
        jdbc.query(
                summary,
                rs -> {
                    counts.put(rs.getString("case_status"), rs.getLong("total"));
                },
                account);
        List<Checkpoint> checkpoints =
                jdbc.query(
                        """
                SELECT completed_until,last_success_at,last_failure_code
                FROM saspay_audit_checkpoints WHERE provider_account_reference=?
                """,
                        (rs, row) ->
                                new Checkpoint(
                                        nullableInstant(rs, "completed_until"),
                                        nullableInstant(rs, "last_success_at"),
                                        rs.getString("last_failure_code")),
                        account);
        return new Page(
                rows,
                total,
                page,
                size,
                counts.getOrDefault("OPEN", 0L),
                counts.getOrDefault("IN_REVIEW", 0L),
                counts.getOrDefault("CLOSED", 0L),
                checkpoints.isEmpty() ? null : checkpoints.getFirst());
    }

    @Transactional(readOnly = true)
    public IncidentDetail get(UUID id) {
        operator();
        Incident item = find(id);
        return new IncidentDetail(item, decisions(id));
    }

    private Incident find(UUID id) {
        List<Incident> items =
                jdbc.query(
                        incidentSelect() + " AND i.id=?",
                        SaspayIncidentConsoleService::incident,
                        account,
                        id);
        if (items.isEmpty())
            throw new ApiException(404, "SASPAY_INCIDENT_NOT_FOUND", "Incident introuvable.");
        return items.getFirst();
    }

    private List<Decision> decisions(UUID id) {
        return jdbc.query(
                """
                SELECT id,action,actor_id,evidence_reference,reason,
                       observed_occurrence_count,recorded_at
                FROM saspay_audit_incident_decisions
                WHERE incident_id=?
                ORDER BY decision_number DESC LIMIT 100
                """,
                (rs, row) ->
                        new Decision(
                                rs.getObject("id", UUID.class),
                                rs.getString("action"),
                                rs.getObject("actor_id", UUID.class),
                                rs.getString("evidence_reference"),
                                rs.getString("reason"),
                                rs.getLong("observed_occurrence_count"),
                                rs.getTimestamp("recorded_at").toInstant()),
                id);
    }

    @Transactional
    public CommandResult decide(
            UUID id,
            String action,
            String reference,
            String reason,
            long expectedOccurrenceCount,
            boolean confirmed) {
        UUID actor = operator();
        if (!confirmed
                || action == null
                || !Set.of("REVIEW_STARTED", "REVIEW_NOTE", "CLOSED").contains(action)
                || reference == null
                || !reference.matches("[A-Za-z0-9][A-Za-z0-9._:-]{4,119}")
                || reason == null
                || reason.strip().length() < 20
                || reason.length() > 500
                || reason.codePoints().anyMatch(Character::isISOControl)
                || expectedOccurrenceCount < 1) {
            throw new ApiException(
                    400,
                    "SASPAY_INCIDENT_DECISION_INVALID",
                    "Motif détaillé, référence justificative et confirmation exigés.");
        }
        // An update of the incident count acquires the same PostgreSQL row lock.
        // This FOR UPDATE serializes decisions with incoming scans and other admins.
        List<Long> occurrences =
                jdbc.query(
                        """
                SELECT occurrence_count FROM saspay_audit_incidents
                WHERE id=? AND provider_account_reference=? FOR UPDATE
                """,
                        (rs, row) -> rs.getLong(1),
                        id,
                        account);
        if (occurrences.isEmpty())
            throw new ApiException(404, "SASPAY_INCIDENT_NOT_FOUND", "Incident introuvable.");
        if (occurrences.getFirst() != expectedOccurrenceCount)
            throw new ApiException(
                    409,
                    "SASPAY_INCIDENT_CHANGED",
                    "L'incident a évolué ; rechargez avant de décider.");
        Incident item = find(id);
        if ("CLOSED".equals(item.status()))
            throw new ApiException(
                    409,
                    "SASPAY_INCIDENT_ALREADY_CLOSED",
                    "Cet incident est déjà clôturé pour cette occurrence.");
        UUID decisionId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO saspay_audit_incident_decisions(
                    id,incident_id,actor_id,action,evidence_reference,reason,
                    observed_occurrence_count,recorded_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                decisionId,
                id,
                actor,
                action,
                reference,
                reason.strip(),
                expectedOccurrenceCount,
                Timestamp.from(clock.instant()));
        return new CommandResult(id, decisionId, "CLOSED".equals(action) ? "CLOSED" : "IN_REVIEW");
    }
}
