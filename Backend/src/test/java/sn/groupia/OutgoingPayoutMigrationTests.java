package sn.groupia;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import liquibase.resource.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class OutgoingPayoutMigrationTests {
    @TempDir Path historical;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void upgradePreservesHistoryAndRejectsCrossFamilyCollisions(boolean collision)
            throws Exception {
        var source = Path.of("src/main/resources/db/changelog");
        var destination = Files.createDirectories(historical.resolve("db/changelog"));
        try (var files = Files.list(source)) {
            for (var file : files.toList())
                Files.copy(file, destination.resolve(file.getFileName()));
        }
        var master =
                Files.readString(source.resolve("db.changelog-master.yaml")).replace("\r\n", "\n");
        Files.writeString(
                destination.resolve("db.changelog-master.yaml"),
                master.substring(0, master.indexOf("  - include:\n      file: 027-")));
        var migrations = new DistributionMigrationTests();
        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            db.start();
            try (var resource = new DirectoryResourceAccessor(historical)) {
                migrations.migrate(db, resource);
            }
            var j =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    db.getJdbcUrl(), db.getUsername(), db.getPassword()));
            var f = MerchantSettlementFixtures.seed(j);
            UUID refund = MerchantSettlementFixtures.refund(j, f, "BICTORYS_PAYOUT"),
                    payout = UUID.randomUUID(),
                    dest = UUID.randomUUID(),
                    receipt = UUID.randomUUID();
            j.update(
                    """
                insert into merchant_payout_destinations(id,organization_id,method,phone_e164,holder_name,status,created_at,updated_at,created_by,updated_by)
                values (?,?,'WAVE','+221771234567','Synthetic holder','ACTIVE',now(),now(),?,?)
                """,
                    dest,
                    f.org(),
                    f.actor(),
                    f.actor());
            j.update(
                    """
                insert into merchant_payouts(id,organization_id,campaign_id,amount,currency,status,destination_id,method,phone_e164,holder_name,provider,provider_account_reference,business_reference,provider_idempotency_key,provider_transaction_id,requested_by,requested_at,reconciliation_required)
                values (?,?,?,10000,'XOF','PENDING',?,'WAVE','+221771234567','Synthetic holder','BICTORYS','sandbox',?,?,?, ?,now(),false)
                """,
                    payout,
                    f.org(),
                    f.campaign(),
                    dest,
                    "MERCHANT_PAYOUT:" + payout,
                    UUID.randomUUID(),
                    collision ? "REFUND-" + refund : "MERCHANT-" + payout,
                    f.actor());
            j.update(
                    "insert into refund_webhook_receipts(id,fingerprint,authentic,refund_id,processing_status,received_at) values (?,repeat('a',64),true,?,'PROCESSED',now())",
                    receipt,
                    refund);
            var refunds = j.queryForList("select * from payment_refunds");
            var payouts = j.queryForList("select * from merchant_payouts");
            var checksums =
                    j.queryForList(
                            "select id,md5sum from databasechangelog order by orderexecuted");
            try (var resource = new ClassLoaderResourceAccessor()) {
                if (collision) {
                    assertThatThrownBy(() -> migrations.migrate(db, resource))
                            .isInstanceOf(Exception.class)
                            .hasStackTraceContaining(
                                    "Outgoing payout historical identity collision");
                    assertThat(
                                    j.queryForObject(
                                            "select to_regclass('outgoing_provider_operations')::text",
                                            String.class))
                            .isNull();
                } else {
                    migrations.migrate(db, resource);
                    assertThat(
                                    j.queryForObject(
                                            "select count(*) from outgoing_provider_operations",
                                            Integer.class))
                            .isEqualTo(2);
                    assertThat(
                                    j.queryForObject(
                                            "select operation_id from refund_webhook_receipts where id=?",
                                            UUID.class,
                                            receipt))
                            .isEqualTo(refund);
                    assertThat(
                                    j.queryForObject(
                                            "select provider_transaction_id from outgoing_provider_operations where operation_id=?",
                                            String.class,
                                            refund))
                            .isEqualTo("REFUND-" + refund);
                    assertThatThrownBy(
                                    () ->
                                            j.update(
                                                    "update outgoing_provider_operations set provider_transaction_id=? where operation_id=?",
                                                    "REFUND-" + refund,
                                                    payout))
                            .isInstanceOf(
                                    org.springframework.dao.DataIntegrityViolationException.class);
                }
            }
            assertThat(j.queryForList("select * from payment_refunds")).isEqualTo(refunds);
            // Successful upgrades add only a nullable target column. A rejected
            // collision rolls back before this additive schema change takes effect.
            var afterPayouts = j.queryForList("select * from merchant_payouts");
            if (!collision) {
                afterPayouts =
                        afterPayouts.stream()
                                .map(MigrationHistoryAssertions::historicalMerchantPayout)
                                .toList();
            }
            assertThat(afterPayouts).isEqualTo(payouts);
            assertThat(
                            j.queryForList(
                                    "select id,md5sum from databasechangelog where orderexecuted<=? order by orderexecuted",
                                    checksums.size()))
                    .isEqualTo(checksums);
        }
    }
}
