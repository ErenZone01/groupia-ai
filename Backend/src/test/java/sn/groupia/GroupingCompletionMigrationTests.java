package sn.groupia;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import liquibase.resource.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class GroupingCompletionMigrationTests {
    @TempDir Path historical;

    @Test
    void upgradePreservesChecksumsFinancialFactsAndUnknownHistoricalDate() throws Exception {
        var source = Path.of("src/main/resources/db/changelog");
        var dest = Files.createDirectories(historical.resolve("db/changelog"));
        try (var files = Files.list(source)) {
            for (var file : files.toList()) Files.copy(file, dest.resolve(file.getFileName()));
        }
        var master =
                Files.readString(source.resolve("db.changelog-master.yaml")).replace("\r\n", "\n");
        Files.writeString(
                dest.resolve("db.changelog-master.yaml"),
                master.substring(0, master.indexOf("  - include:\n      file: 025-")));
        var runner = new DistributionMigrationTests();
        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            db.start();
            try (var resources = new DirectoryResourceAccessor(historical)) {
                runner.migrate(db, resources);
            }
            var jdbc =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    db.getJdbcUrl(), db.getUsername(), db.getPassword()));
            var f = MerchantSettlementFixtures.seed(jdbc);
            MerchantSettlementFixtures.settledBefore026(jdbc, f, "INITIAL_ORDER");
            jdbc.update(
                    "update grouping_campaigns set status='COMPLETED' where id=?", f.campaign());
            var history =
                    jdbc.queryForList(
                            "select (to_jsonb(r)-'target_type'-'target_id')::text as historical from payment_requests r order by id");
            var checksums = MigrationHistoryAssertions.snapshot(jdbc);
            try (var resources = new ClassLoaderResourceAccessor()) {
                runner.migrate(db, resources);
            }
            MigrationHistoryAssertions.assertPreserved(jdbc, checksums);
            assertThat(
                            jdbc.queryForList(
                                    "select (to_jsonb(r)-'target_type'-'target_id'-'direct_order_id')::text as historical from payment_requests r order by id"))
                    .isEqualTo(history);
            assertThat(jdbc.queryForObject(
                            "select count(*) from payment_requests where direct_order_id is not null",
                            Long.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select completed_at from grouping_campaigns where id=?",
                                    Object.class,
                                    f.campaign()))
                    .isNull();
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "update grouping_campaigns set status='DISTRIBUTING' where id=?",
                                            f.campaign()))
                    .hasStackTraceContaining("terminal");
            var next = MerchantSettlementFixtures.seed(jdbc, f);
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "update grouping_campaigns set status='COMPLETED',completed_at=now() where id=?",
                                            next.campaign()))
                    .hasStackTraceContaining("distribution");
            jdbc.update(
                    "update grouping_campaigns set status='DISTRIBUTING' where id=?",
                    next.campaign());
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "update grouping_campaigns set status='COMPLETED' where id=?",
                                            next.campaign()))
                    .hasStackTraceContaining("server date");
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "update grouping_campaigns set status='COMPLETED',completed_at=created_at-interval '1 day' where id=?",
                                            next.campaign()))
                    .hasStackTraceContaining("ck_grouping_completion_date");
            jdbc.update(
                    "update grouping_campaigns set status='COMPLETED',completed_at=now() where id=?",
                    next.campaign());
        }
    }
}
