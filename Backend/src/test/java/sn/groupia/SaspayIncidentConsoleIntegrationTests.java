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
import sn.groupia.payment.infrastructure.provider.saspay.SaspayIncidentConsoleService;
import sn.groupia.payment.infrastructure.provider.saspay.SaspayProperties;
import sn.groupia.shared.api.ApiException;
import sn.groupia.shared.api.PlatformCurrentActor;

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
                    INSERT INTO saspay_audit_incidents(
                      id,provider_account_reference,incident_code,reference_fingerprint,
                      first_seen_at,last_seen_at,occurrence_count)
                    VALUES (?,?, 'WEBHOOK_STUCK',repeat('a',64),now(),now(),1)
                    """,
                    incident,
                    merchant.toString());

            PlatformCurrentActor identity = () -> actor;
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

    @Test
    void accountingSeparatesOrganizationsAndUnallocatedCashWithoutOffsettingDeficits()
            throws Exception {
        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            db.start();
            try (var resources = new ClassLoaderResourceAccessor()) {
                new DistributionMigrationTests().migrate(db, resources);
            }
            var jdbc =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    db.getJdbcUrl(), db.getUsername(), db.getPassword()));
            UUID merchant = UUID.randomUUID(), operator = UUID.randomUUID();
            var props = properties(merchant);
            var allowed =
                    new SaspayIncidentConsoleService(
                            jdbc, () -> operator, props, CLOCK, true, operator.toString());
            var diagnostic =
                    new sn.groupia.payment.infrastructure.provider.saspay
                            .SaspayAccountingDiagnostic(jdbc, props, allowed, CLOCK);
            var a = MerchantSettlementFixtures.seed(jdbc);
            var b = MerchantSettlementFixtures.seed(jdbc);
            var unallocated = MerchantSettlementFixtures.seed(jdbc);
            var otherAccount = MerchantSettlementFixtures.seed(jdbc);
            for (var fixture : List.of(a, b)) {
                UUID request =
                        MerchantSettlementFixtures.settled(
                                jdbc, fixture, "INITIAL_ORDER", "SASPAY", merchant.toString());
                jdbc.update(
                        """
                    INSERT INTO provider_collections(id,organization_id,payment_request_id,payment_id,
                        target_type,target_id,purpose,provider,provider_account,provider_transaction_id,
                        currency,principal,seller_principal,platform_fee,confirmed_at)
                    SELECT gen_random_uuid(),p.organization_id,p.payment_request_id,p.id,'ORDER',r.order_id,
                        r.purpose,p.provider,p.provider_account_reference,p.provider_transaction_id,'XOF',25300,25000,300,now()
                    FROM payments p JOIN payment_requests r ON r.id=p.payment_request_id WHERE r.id=?
                    """,
                        request);
            }
            MerchantSettlementFixtures.settled(
                    jdbc, unallocated, "INITIAL_ORDER", "SASPAY", merchant.toString());
            MerchantSettlementFixtures.settled(
                    jdbc, otherAccount, "INITIAL_ORDER", "SASPAY", UUID.randomUUID().toString());
            payout(jdbc, a, merchant, 1000, "SUCCEEDED", true);
            payout(jdbc, a, merchant, 200, "PENDING", false);
            payout(jdbc, b, merchant, 26000, "SUCCEEDED", false);
            var before = jdbc.queryForList("select * from payments order by id");
            var snapshot = diagnostic.snapshot();
            assertThat(snapshot.organizations()).hasSize(3);
            assertThat(snapshot.organizations())
                    .noneMatch(row -> row.organizationId().equals(otherAccount.org()));
            assertThat(snapshot.retainedMerchantPrincipal()).isEqualByComparingTo("24000");
            assertThat(snapshot.merchantDeficits()).isEqualByComparingTo("1000");
            assertThat(snapshot.unallocatedCollections()).isEqualByComparingTo("25300");
            assertThat(snapshot.subscriptionRevenue()).isZero();
            assertThat(snapshot.reservedPayouts()).isEqualByComparingTo("200");
            assertThat(snapshot.knownNetWalletFlows()).isEqualByComparingTo("48900");
            assertThat(snapshot.missingFeeProofs()).isEqualTo(4);
            assertThat(
                            snapshot.organizations().stream()
                                    .filter(row -> row.organizationId().equals(a.org()))
                                    .findFirst()
                                    .orElseThrow()
                                    .payoutFees())
                    .isEqualByComparingTo("37");
            assertThat(jdbc.queryForList("select * from payments order by id")).isEqualTo(before);
            var denied =
                    new SaspayIncidentConsoleService(
                            jdbc, () -> a.actor(), props, CLOCK, true, operator.toString());
            assertThatThrownBy(
                            () ->
                                    new sn.groupia.payment.infrastructure.provider.saspay
                                                    .SaspayAccountingDiagnostic(
                                                    jdbc, props, denied, CLOCK)
                                            .snapshot())
                    .isInstanceOf(ApiException.class);
        }
    }

    private static void payout(
            JdbcTemplate j,
            MerchantSettlementFixtures.Fixture fixture,
            UUID account,
            long amount,
            String status,
            boolean feeProof) {
        var destinations =
                j.queryForList(
                        "select id from merchant_payout_destinations where organization_id=?",
                        UUID.class,
                        fixture.org());
        UUID destination = destinations.isEmpty() ? UUID.randomUUID() : destinations.getFirst();
        if (destinations.isEmpty())
            j.update(
                    """
            insert into merchant_payout_destinations(id,organization_id,method,phone_e164,holder_name,status,created_at,updated_at,created_by,updated_by)
            values (?,?,'WAVE','+221771234567','Fixture','ACTIVE',now(),now(),?,?)
            """,
                    destination,
                    fixture.org(),
                    fixture.actor(),
                    fixture.actor());
        UUID id = UUID.randomUUID();
        j.update(
                """
            insert into merchant_payouts(id,organization_id,campaign_id,amount,currency,status,destination_id,
                method,phone_e164,holder_name,provider,provider_account_reference,business_reference,
                provider_idempotency_key,provider_transaction_id,requested_by,requested_at,succeeded_at,reconciliation_required)
            values (?,?,?,?,'XOF',?,?,'WAVE','+221771234567','Fixture','SASPAY',?,?,?,?,?,now(),
                CASE WHEN ?='SUCCEEDED' THEN now() ELSE NULL END,false)
            """,
                id,
                fixture.org(),
                fixture.campaign(),
                amount,
                status,
                destination,
                account.toString(),
                "TEST:" + id,
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                fixture.actor(),
                status);
        if (feeProof)
            j.update(
                    """
            insert into merchant_payout_provider_fees(payout_id,principal_xof,provider_fee_xof,wallet_debited_xof,fee_charge_mode)
            values (?,?,37,?,'DEDUCTED')
            """,
                    id,
                    amount,
                    amount);
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
