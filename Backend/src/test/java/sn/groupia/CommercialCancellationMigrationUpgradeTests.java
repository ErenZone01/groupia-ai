package sn.groupia;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import liquibase.resource.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class CommercialCancellationMigrationUpgradeTests {
    private static final String ORIGINAL = "9:2e225b38cb346c51a9f5f2af9a9b21e1";
    private static final String WITH_ACTOR = "9:cee7dcfde2808586052973a6183bfaa5";
    @TempDir Path historical;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "original",
                "withActor",
                "unknown",
                "wrongType",
                "wrongNullability",
                "wrongDefault"
            })
    void upgradesOnlyKnown050VersionsWithoutRewritingMoneyOrHistory(String version)
            throws Exception {
        prepare(version);
        var migration = new DistributionMigrationTests();
        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            db.start();
            try (var resources = new DirectoryResourceAccessor(historical)) {
                migration.migrate(db, resources);
            }
            var jdbc =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    db.getJdbcUrl(), db.getUsername(), db.getPassword()));
            var checksum =
                    jdbc.queryForObject(
                            "select md5sum from databasechangelog where id='050-commercial-cancellation-states'",
                            String.class);
            if (version.equals("withActor")) assertThat(checksum).isEqualTo(WITH_ACTOR);
            else if (version.equals("unknown")) assertThat(checksum).isNotIn(ORIGINAL, WITH_ACTOR);
            else assertThat(checksum).isEqualTo(ORIGINAL);

            var fixture = MerchantSettlementFixtures.seed(jdbc);
            MerchantSettlementFixtures.settled(jdbc, fixture, "INITIAL_ORDER");
            UUID actor = fixture.actor();
            if (version.equals("withActor"))
                jdbc.update(
                        "update grouping_campaigns set cancelled_by=? where id=?",
                        actor,
                        fixture.campaign());
            if (version.equals("wrongType"))
                jdbc.execute("alter table grouping_campaigns add column cancelled_by text");
            if (version.equals("wrongNullability"))
                jdbc.execute(
                        "alter table grouping_campaigns add column cancelled_by uuid not null default gen_random_uuid()");
            if (version.equals("wrongDefault"))
                jdbc.execute(
                        "alter table grouping_campaigns add column cancelled_by uuid default gen_random_uuid()");
            var campaigns = jdbc.queryForList("select * from grouping_campaigns order by id");
            var before = new LinkedHashMap<String, List<Map<String, Object>>>();
            for (var table : List.of("orders", "payment_requests", "payments"))
                before.put(table, jdbc.queryForList("select * from " + table + " order by id"));
            var history = MigrationHistoryAssertions.snapshot(jdbc);
            boolean accepted = version.equals("original") || version.equals("withActor");
            try (var resources = new ClassLoaderResourceAccessor()) {
                if (accepted) {
                    migration.migrate(db, resources);
                    migration.migrate(db, resources);
                } else {
                    assertThatThrownBy(() -> migration.migrate(db, resources))
                            .hasStackTraceContaining(
                                    version.equals("unknown")
                                            ? "ValidationFailedException"
                                            : "Unsupported campaign cancellation actor column");
                }
            }
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from databasechangelog where id='051-campaign-cancellation-actor'",
                                    Integer.class))
                    .isEqualTo(accepted ? 1 : 0);
            MigrationHistoryAssertions.assertPreserved(jdbc, history);
            for (var entry : before.entrySet())
                assertThat(jdbc.queryForList("select * from " + entry.getKey() + " order by id"))
                        .isEqualTo(entry.getValue());
            var afterCampaigns = jdbc.queryForList("select * from grouping_campaigns order by id");
            if (version.equals("original")) {
                afterCampaigns.forEach(
                        row -> {
                            assertThat(row).containsKey("cancelled_by");
                            assertThat(row.remove("cancelled_by")).isNull();
                        });
            }
            assertThat(afterCampaigns).isEqualTo(campaigns);
            if (accepted) {
                assertActorColumn(jdbc);
                jdbc.update(
                        "update grouping_campaigns set cancelled_by=? where id=?",
                        actor,
                        fixture.campaign());
                assertThat(
                                jdbc.queryForObject(
                                        "select cancelled_by from grouping_campaigns where id=?",
                                        UUID.class,
                                        fixture.campaign()))
                        .isEqualTo(actor);
            }
        }
    }

    @Test
    void freshDatabaseHasCancellationActorAndReapplyingDoesNotChangeHistory() throws Exception {
        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            db.start();
            var jdbc =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    db.getJdbcUrl(), db.getUsername(), db.getPassword()));
            try (var resources = new ClassLoaderResourceAccessor()) {
                var migration = new DistributionMigrationTests();
                migration.migrate(db, resources);
                var before = MigrationHistoryAssertions.snapshot(jdbc);
                assertActorColumn(jdbc);
                migration.migrate(db, resources);
                assertThat(MigrationHistoryAssertions.snapshot(jdbc)).isEqualTo(before);
            }
        }
    }

    private void assertActorColumn(JdbcTemplate jdbc) {
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from information_schema.columns where table_schema=current_schema() and table_name='grouping_campaigns' and column_name='cancelled_by' and udt_name='uuid' and is_nullable='YES' and column_default is null",
                                Integer.class))
                .isEqualTo(1);
    }

    private void prepare(String version) throws Exception {
        var source = Path.of("src/main/resources/db/changelog");
        var dest = Files.createDirectories(historical.resolve("db/changelog"));
        try (var paths = Files.list(source)) {
            for (var p : paths.toList()) Files.copy(p, dest.resolve(p.getFileName()));
        }
        var master =
                Files.readString(source.resolve("db.changelog-master.yaml")).replace("\r\n", "\n");
        Files.writeString(
                dest.resolve("db.changelog-master.yaml"),
                master.substring(0, master.indexOf("  - include:\n      file: 051-")));
        var sql =
                Files.readString(source.resolve("050-commercial-cancellation-states.sql"))
                        .replace("\r\n", "\n")
                        .replace("--validCheckSum: " + WITH_ACTOR + "\n", "")
                        .replace(
                                "-- Original 050 restored; the known variant with campaign actor is upgraded by 051.\n",
                                "");
        if (version.equals("withActor"))
            sql += "\nALTER TABLE grouping_campaigns ADD COLUMN cancelled_by UUID;\n";
        if (version.equals("unknown"))
            sql += "\nALTER TABLE grouping_campaigns ADD COLUMN unknown_fixture_flag BOOLEAN;\n";
        Files.writeString(dest.resolve("050-commercial-cancellation-states.sql"), sql);
    }
}
