package sn.groupia;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Proves the new schema does not require changes to any existing transaction. */
class SaspayIncidentMigrationTests {
    @Test
    void incidentEvidenceCannotBeDeletedOrReassignedAndCheckpointCannotRewind()
            throws Exception {
        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            db.start();
            try (var resource = new ClassLoaderResourceAccessor()) {
                new DistributionMigrationTests().migrate(db, resource);
            }
            var jdbc = new JdbcTemplate(
                    new DriverManagerDataSource(
                            db.getJdbcUrl(), db.getUsername(), db.getPassword()));
            String account = UUID.randomUUID().toString();
            UUID id = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO saspay_audit_checkpoints(provider_account_reference,completed_until) VALUES (?,?::timestamptz)",
                    account, "2026-11-18T00:00:00Z");
            jdbc.update(
                    """
                    INSERT INTO saspay_audit_incidents
                    (id,provider_account_reference,incident_code,reference_fingerprint,
                     first_seen_at,last_seen_at)
                    VALUES (?,?, 'WEBHOOK_STUCK',repeat('a',64),now(),now())
                    """,
                    id, account);
            jdbc.update(
                    """
                    INSERT INTO saspay_audit_incidents
                    (id,provider_account_reference,incident_code,reference_fingerprint,
                     first_seen_at,last_seen_at)
                    VALUES (?,?, 'WEBHOOK_STUCK',repeat('a',64),now(),now())
                    ON CONFLICT(provider_account_reference,incident_code,reference_fingerprint)
                    DO UPDATE SET occurrence_count=saspay_audit_incidents.occurrence_count+1,
                                  last_seen_at=greatest(saspay_audit_incidents.last_seen_at,excluded.last_seen_at)
                    """,
                    UUID.randomUUID(), account);
            assertThat(jdbc.queryForObject(
                    "SELECT occurrence_count FROM saspay_audit_incidents WHERE id=?",
                    Long.class, id)).isEqualTo(2L);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE saspay_audit_incidents SET incident_code='PAYOUT_REVIEW_PENDING' WHERE id=?",
                    id)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "DELETE FROM saspay_audit_incidents WHERE id=?", id))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE saspay_audit_checkpoints SET completed_until=?::timestamptz WHERE provider_account_reference=?",
                    "2026-11-17T00:00:00Z", account))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM saspay_audit_incidents", Integer.class))
                    .isEqualTo(1);
        }
    }
}
