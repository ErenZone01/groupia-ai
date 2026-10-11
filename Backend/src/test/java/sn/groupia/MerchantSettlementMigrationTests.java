package sn.groupia;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import liquibase.resource.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class MerchantSettlementMigrationTests {
    @TempDir Path historical;

    @Test
    void backfills019ExactlyOnceWithoutChargingMerchantFundedRefunds() throws Exception {
        var source = Path.of("src/main/resources/db/changelog");
        var dest = Files.createDirectories(historical.resolve("db/changelog"));
        try (var paths = Files.list(source)) {
            for (var p : paths.toList()) Files.copy(p, dest.resolve(p.getFileName()));
        }
        var master =
                Files.readString(source.resolve("db.changelog-master.yaml")).replace("\r\n", "\n");
        Files.writeString(
                dest.resolve("db.changelog-master.yaml"),
                master.substring(0, master.indexOf("  - include:\n      file: 020-")));
        var migrations = new DistributionMigrationTests();
        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            db.start();
            try (var r = new DirectoryResourceAccessor(historical)) {
                migrations.migrate(db, r);
            }
            var j =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    db.getJdbcUrl(), db.getUsername(), db.getPassword()));
            var platform = MerchantSettlementFixtures.seed(j);
            var manual = MerchantSettlementFixtures.seed(j);
            MerchantSettlementFixtures.settled(j, platform, "INITIAL_ORDER");
            MerchantSettlementFixtures.settled(j, platform, "SHIPPING_BALANCE");
            MerchantSettlementFixtures.settled(j, manual, "INITIAL_ORDER");
            UUID auto = MerchantSettlementFixtures.refund(j, platform, "BICTORYS_PAYOUT"),
                    own = MerchantSettlementFixtures.refund(j, manual, "MANUAL_EXTERNAL");
            var checksums =
                    j.queryForList(
                            "select id,md5sum from databasechangelog order by orderexecuted");
            var originals = new HashMap<String, List<Map<String, Object>>>();
            for (var table : List.of("orders", "payment_requests", "payments", "payment_refunds"))
                originals.put(table, j.queryForList("select * from " + table + " order by id"));
            try (var r = new ClassLoaderResourceAccessor()) {
                migrations.migrate(db, r);
            }
            assertThat(
                            j.queryForObject(
                                    "select count(*) from merchant_ledger_entries", Integer.class))
                    .isEqualTo(4);
            assertThat(
                            j.queryForObject(
                                    "select sum(case direction when 'CREDIT' then amount else -amount end) from merchant_ledger_entries where organization_id=?",
                                    java.math.BigDecimal.class,
                                    platform.org()))
                    .isEqualByComparingTo("26500");
            assertThat(
                            j.queryForObject(
                                    "select amount from merchant_ledger_entries where source_id=?",
                                    java.math.BigDecimal.class,
                                    auto))
                    .isEqualByComparingTo("500");
            assertThat(
                            j.queryForObject(
                                    "select count(*) from merchant_ledger_entries where source_id=?",
                                    Integer.class,
                                    own))
                    .isZero();
            assertThat(
                            j.queryForObject(
                                    "select sum(platform_fee_amount) from merchant_ledger_entries",
                                    java.math.BigDecimal.class))
                    .isEqualByComparingTo("600");
            assertThat(
                            j.queryForObject(
                                    "select count(*) from merchant_ledger_entries l join payment_requests r on r.id=l.source_id where l.recorded_at<>r.closed_at",
                                    Integer.class))
                    .isZero();
            assertThat(
                            j.queryForObject(
                                    "select count(*) from merchant_ledger_entries l join payment_refunds r on r.id=l.source_id where l.recorded_at<>r.completed_at",
                                    Integer.class))
                    .isZero();
            for (var table : originals.keySet())
                assertThat(j.queryForList("select * from " + table + " order by id"))
                        .isEqualTo(originals.get(table));
            assertThat(
                            j.queryForList(
                                    "select id,md5sum from databasechangelog where id not like '020-%' and id not like '021-%' and id not like '022-%' and id not like '023-%' and id not like '024-%' order by orderexecuted"))
                    .isEqualTo(checksums);
            assertThatThrownBy(() -> j.update("delete from merchant_ledger_entries"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> j.update("update merchant_ledger_entries set amount=1"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            var ledgerBefore = j.queryForList("select * from merchant_ledger_entries order by id");
            var migration = Files.readString(source.resolve("020-merchant-settlement.sql"));
            // Exercise the backfill SQL again, not just Liquibase's already-executed changeset
            // guard.
            j.execute(
                    migration.substring(
                            migration.indexOf("INSERT INTO merchant_ledger_entries"),
                            migration.indexOf("--changeset groupia:020-merchant-history-guards")));
            assertThat(j.queryForList("select * from merchant_ledger_entries order by id"))
                    .isEqualTo(ledgerBefore);
            try (var r = new ClassLoaderResourceAccessor()) {
                migrations.migrate(db, r);
            }
            assertThat(
                            j.queryForObject(
                                    "select count(*) from merchant_ledger_entries", Integer.class))
                    .isEqualTo(4);
        }
    }
}
