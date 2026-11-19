package sn.groupia;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import sn.groupia.identity.api.CurrentUser;
import sn.groupia.payment.infrastructure.provider.saspay.SaspayIncidentConsoleService;
import sn.groupia.payment.infrastructure.provider.saspay.SaspayProperties;
import sn.groupia.shared.api.ApiException;

class SaspayIncidentConsoleIntegrationTests {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-19T17:30:00Z"), ZoneOffset.UTC);

    @Test
    void authorizedOperatorCanReviewCloseAndReopenWithoutChangingFinancialTables()
            throws Exception {
        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            db.start();
            try (var resources = new ClassLoaderResourceAccessor()) {
                new DistributionMigrationTests().migrate(db, resources);
            }
            var dataSource =
                    new DriverManagerDataSource(
                            db.getJdbcUrl(), db.getUsername(), db.getPassword());
            var jdbc = new JdbcTemplate(dataSource);
            var transactions =
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            UUID actor = UUID.randomUUID();
            UUID incident = UUID.randomUUID();
            UUID merchant = UUID.randomUUID();
            var props = properties(merchant);
            jdbc.update(
                    """
                    INSERT INTO user_accounts(id,phone_number,password_hash,status,created_at,updated_at)
                    VALUES (?,'+221771234567','synthetic-hash','ACTIVE',now(),now())
                    """,
                    actor);
            jdbc.update(
                    """
                    INSERT INTO saspay_audit_incidents(
                      id,provider_account_reference,incident_code,reference_fingerprint,
                      first_seen_at,last_seen_at,occurrence_count)
                    VALUES (?,?, 'WEBHOOK_STUCK',repeat('a',64),now(),now(),1)
                    """,
                    incident,
                    merchant.toString());

            CurrentUser identity = () -> actor;
            var service =
                    new SaspayIncidentConsoleService(
                            jdbc, identity, props, CLOCK, true, actor.toString());

            assertThat(service.access().allowed()).isTrue();
            var firstPage = service.list("OPEN", "", 0, 20);
            assertThat(firstPage.total()).isEqualTo(1);
            assertThat(firstPage.items().getFirst().referenceHint()).isEqualTo("aaaaaaaaaaaa");
            assertThat(firstPage.items().getFirst().status()).isEqualTo("OPEN");

            transactions.executeWithoutResult(
                    t ->
                            service.decide(
                                    incident,
                                    "REVIEW_STARTED",
                                    "DOSSIER-2026-01",
                                    "Investigations de rapprochement ouvertes avec le fournisseur.",
                                    1,
                                    true));
            assertThat(service.get(incident).incident().status()).isEqualTo("IN_REVIEW");

            transactions.executeWithoutResult(
                    t ->
                            service.decide(
                                    incident,
                                    "REVIEW_NOTE",
                                    "PREUVE-2026-01",
                                    "Relecture indépendante du statut de transaction et de la date.",
                                    1,
                                    true));
            assertThat(service.get(incident).decisions()).hasSize(2);

            transactions.executeWithoutResult(
                    t ->
                            service.decide(
                                    incident,
                                    "CLOSED",
                                    "DOSSIER-2026-CLOS",
                                    "Dossier administratif revu avec justificatifs, sans transfert financier.",
                                    1,
                                    true));
            var closed = service.get(incident);
            assertThat(closed.incident().status()).isEqualTo("CLOSED");
            assertThat(closed.decisions()).hasSize(3);
            assertThat(service.list("CLOSED", "", 0, 20).total()).isEqualTo(1);

            assertThatThrownBy(
                            () ->
                                    transactions.executeWithoutResult(
                                            t ->
                                                    service.decide(
                                                            incident,
                                                            "CLOSED",
                                                            "DOSSIER-2026-CLOS",
                                                            "Une seconde clôture ne doit jamais être enregistrée.",
                                                            1,
                                                            true)))
                    .isInstanceOf(ApiException.class);

            // A new provider observation reopens the case without deleting old decisions.
            jdbc.update(
                    """
                    UPDATE saspay_audit_incidents
                    SET occurrence_count=occurrence_count+1,last_seen_at=now()
                    WHERE id=?
                    """,
                    incident);
            assertThat(service.get(incident).incident().status()).isEqualTo("OPEN");
            assertThat(service.get(incident).decisions()).hasSize(3);
            assertThatThrownBy(
                            () ->
                                    transactions.executeWithoutResult(
                                            t ->
                                                    service.decide(
                                                            incident,
                                                            "REVIEW_STARTED",
                                                            "DOSSIER-2026-02",
                                                            "Décision prise avec une version périmée de l'incident.",
                                                            1,
                                                            true)))
                    .isInstanceOf(ApiException.class);

            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "DELETE FROM saspay_audit_incident_decisions WHERE incident_id=?",
                                            incident))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Long.class))
                    .isEqualTo(0);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_payouts", Long.class))
                    .isEqualTo(0);
        }
    }

    @Test
    void operatorWhitelistAndAccountScopeDenyUnauthorizedAccess() throws Exception {
        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            db.start();
            try (var resources = new ClassLoaderResourceAccessor()) {
                new DistributionMigrationTests().migrate(db, resources);
            }
            var jdbc =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    db.getJdbcUrl(), db.getUsername(), db.getPassword()));
            UUID operator = UUID.randomUUID();
            UUID merchant = UUID.randomUUID();
            var props = properties(merchant);
            var denied =
                    new SaspayIncidentConsoleService(
                            jdbc, () -> operator, props, CLOCK, true, UUID.randomUUID().toString());
            assertThat(denied.access().allowed()).isFalse();
            assertThatThrownBy(() -> denied.list(null, null, 0, 20))
                    .isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> denied.get(UUID.randomUUID()))
                    .isInstanceOf(ApiException.class);
            assertThatThrownBy(
                            () ->
                                    denied.decide(
                                            UUID.randomUUID(),
                                            "CLOSED",
                                            "DOSSIER-2026",
                                            "Cette décision ne doit jamais être autorisée pour ce compte.",
                                            1,
                                            true))
                    .isInstanceOf(ApiException.class);

            var allowed =
                    new SaspayIncidentConsoleService(
                            jdbc, () -> operator, props, CLOCK, true, operator.toString());
            assertThat(allowed.list(null, null, 0, 20).total()).isZero();
            assertThatThrownBy(() -> allowed.get(UUID.randomUUID()))
                    .isInstanceOf(ApiException.class);
        }
    }

    private static SaspayProperties properties(UUID merchant) {
        return new SaspayProperties(
                true,
                null,
                "sk_test_synthetic",
                "synthetic-secret",
                merchant,
                Set.of("pay.saspay.me"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(4),
                false,
                null,
                null);
    }
}
