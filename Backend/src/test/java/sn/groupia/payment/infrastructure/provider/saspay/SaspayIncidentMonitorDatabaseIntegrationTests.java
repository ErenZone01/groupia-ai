package sn.groupia.payment.infrastructure.provider.saspay;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Reproduces the LIVE startup path against PostgreSQL, not just mocked JdbcTemplate queries. */
class SaspayIncidentMonitorDatabaseIntegrationTests {
    @Test
    void failedBootstrapsShareOneIncidentThenSuccessfulScanAdvancesCheckpoint() throws Exception {
        try (var postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            postgres.start();
            try (var resources = new ClassLoaderResourceAccessor()) {
                var database =
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(
                                        new JdbcConnection(
                                                DriverManager.getConnection(
                                                        postgres.getJdbcUrl(),
                                                        postgres.getUsername(),
                                                        postgres.getPassword())));
                try (var liquibase =
                        new Liquibase(
                                "db/changelog/db.changelog-master.yaml", resources, database)) {
                    liquibase.update(new Contexts(), new LabelExpression());
                }
            }
            var jdbc =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword()));
            var properties = SaspayTestConfiguration.properties();
            String merchant = properties.merchantId().toString();
            AtomicReference<Instant> instant =
                    new AtomicReference<>(Instant.parse("2026-11-21T02:00:00Z"));
            Clock clock =
                    new Clock() {
                        @Override
                        public ZoneId getZone() {
                            return ZoneOffset.UTC;
                        }

                        @Override
                        public Clock withZone(ZoneId zone) {
                            return Clock.fixed(instant.get(), zone);
                        }

                        @Override
                        public Instant instant() {
                            return instant.get();
                        }
                    };

            var history = mock(SaspayHistoryAudit.class);
            when(history.compare(
                            any(Instant.class),
                            any(Instant.class),
                            any(SaspayHistoryAudit.IssueSink.class)))
                    .thenThrow(new SaspayException("SASPAY_HTTP_429", 429, "NONE"))
                    .thenThrow(new SaspayException("SASPAY_HTTP_429", 429, "NONE"))
                    .thenReturn(new SaspayHistoryAudit.Result(1, 0, 0, 0, 0));
            var monitor = new SaspayIncidentMonitor(jdbc, history, properties, clock, "");

            // Previous LIVE behavior never reached the provider: raw Instant JDBC
            // parameters failed during the first local scan. This call now reaches history.
            assertThatThrownBy(monitor::runCycle).isInstanceOf(SaspayException.class);
            Instant first = instant.get();
            instant.set(first.plus(Duration.ofMinutes(15)));
            assertThatThrownBy(monitor::runCycle).isInstanceOf(SaspayException.class);

            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM saspay_audit_incidents WHERE incident_code='AUDIT_INCOMPLETE' AND provider_account_reference=?",
                                    Long.class,
                                    merchant))
                    .isEqualTo(1L);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT occurrence_count FROM saspay_audit_incidents WHERE incident_code='AUDIT_INCOMPLETE' AND provider_account_reference=?",
                                    Long.class,
                                    merchant))
                    .isEqualTo(1L);
            var state =
                    jdbc.queryForMap(
                            "SELECT completed_until,last_failure_code FROM saspay_audit_checkpoints WHERE provider_account_reference=?",
                            merchant);
            assertThat(state.get("completed_until")).isNull();
            assertThat(state.get("last_failure_code")).isEqualTo("PROVIDER_HISTORY_READ");

            instant.set(first.plus(Duration.ofMinutes(30)));
            monitor.runCycle();

            Timestamp completed =
                    jdbc.queryForObject(
                            "SELECT completed_until FROM saspay_audit_checkpoints WHERE provider_account_reference=?",
                            Timestamp.class,
                            merchant);
            assertThat(completed).isNotNull();
            assertThat(completed.toInstant()).isAfter(first.minus(Duration.ofHours(48)));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT last_failure_code FROM saspay_audit_checkpoints WHERE provider_account_reference=?",
                                    String.class,
                                    merchant))
                    .isNull();
            // An unresolved local webhook is seen on every 15-minute cycle.
            // Identical source state MUST NOT reopen an administratively closed case.
            String sourceId = UUID.randomUUID().toString();
            String unchanged = "[\"REVIEW_REQUIRED\",\"UNKNOWN_REFERENCE\",2,5]";
            monitor.recordLocalSignal("WEBHOOK_STUCK", sourceId, unchanged);
            String signalHash = SaspayIncidentMonitor.fingerprint(sourceId);
            UUID incidentId =
                    jdbc.queryForObject(
                            """
                            SELECT id FROM saspay_audit_incidents
                            WHERE provider_account_reference=? AND incident_code='WEBHOOK_STUCK'
                              AND reference_fingerprint=?
                            """,
                            UUID.class,
                            merchant,
                            signalHash);
            assertThat(incidentId).isNotNull();
            UUID operator = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO saspay_audit_incident_decisions
                      (id,incident_id,actor_id,action,evidence_reference,reason,
                       observed_occurrence_count,recorded_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    """,
                    UUID.randomUUID(),
                    incidentId,
                    operator,
                    "CLOSED",
                    "TEST-CASE-2026",
                    "Administratively reviewed and documented for this exact source state.",
                    1L,
                    Timestamp.from(clock.instant()));
            var console =
                    new SaspayIncidentConsoleService(
                            jdbc, () -> operator, properties, clock, true, operator.toString());
            assertThat(console.get(incidentId).incident().status()).isEqualTo("CLOSED");
            instant.set(first.plus(Duration.ofMinutes(45)));
            monitor.recordLocalSignal("WEBHOOK_STUCK", sourceId, unchanged);
            assertThat(console.get(incidentId).incident().status()).isEqualTo("CLOSED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT occurrence_count FROM saspay_audit_incidents WHERE id=?",
                                    Long.class,
                                    incidentId))
                    .isEqualTo(1L);

            // A source workflow version change is new evidence and MUST reopen the case.
            instant.set(first.plus(Duration.ofMinutes(60)));
            monitor.recordLocalSignal(
                    "WEBHOOK_STUCK", sourceId, "[\"REVIEW_REQUIRED\",\"UNKNOWN_REFERENCE\",3,6]");
            assertThat(console.get(incidentId).incident().status()).isEqualTo("OPEN");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT occurrence_count FROM saspay_audit_incidents WHERE id=?",
                                    Long.class,
                                    incidentId))
                    .isEqualTo(2L);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM saspay_audit_local_signal_states",
                                    Long.class))
                    .isEqualTo(2L); // one monitor failure and one webhook

            assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_payouts", Long.class))
                    .isZero();
        }
    }

    @Test
    void errorsAreCategorizedWithoutLoggingRawExceptionMessages() {
        assertThat(
                        SaspayIncidentMonitor.failureCode(
                                "LOCAL_SCAN",
                                new DataAccessResourceFailureException("private-db-hostname")))
                .isEqualTo("DATABASE_LOCAL_SCAN");
        assertThat(
                        SaspayIncidentMonitor.failureCode(
                                "HISTORY_READ", new SaspayException("SASPAY_HTTP_429")))
                .isEqualTo("PROVIDER_HISTORY_READ");
        assertThat(
                        SaspayIncidentMonitor.safeSqlState(
                                new IllegalStateException(
                                        "private connection detail",
                                        new java.sql.SQLException("sensitive", "22007"))))
                .isEqualTo("22007");
        assertThat(SaspayIncidentMonitor.safeSqlState(new IllegalStateException("private")))
                .isEqualTo("NONE");
    }
}
