package sn.groupia;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import liquibase.resource.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class DistributionChecksumUpgradeTests {
    private static final String ORIGINAL = "9:af50ee455b776733e7a54bcd9d512e3d";
    private static final String WITH_SEQUENCE = "9:c94ed11d0a9ca499454b514b08148d07";
    @TempDir Path historical;

    @ParameterizedTest
    @ValueSource(strings = {"original", "withSequence", "unknown"})
    void upgradesOnlyKnown017SchemasWithoutRewritingHistory(String version) throws Exception {
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
            String checksum =
                    jdbc.queryForObject(
                            "select md5sum from databasechangelog where id='017-distribution'",
                            String.class);
            if (version.equals("original")) assertThat(checksum).isEqualTo(ORIGINAL);
            else if (version.equals("withSequence")) assertThat(checksum).isEqualTo(WITH_SEQUENCE);
            else assertThat(checksum).isNotIn(ORIGINAL, WITH_SEQUENCE);
            UUID order = migration.seedOrder(jdbc);
            // Deliberately different physical insertion and chronological order.
            UUID last = append(jdbc, order, "READY_FOR_PICKUP", "2026-10-03T12:00:03Z", null);
            UUID first = append(jdbc, order, "READY_FOR_PICKUP", "2026-10-03T12:00:01Z", null);
            UUID middle =
                    append(
                            jdbc,
                            order,
                            "READINESS_REVOKED",
                            "2026-10-03T12:00:02Z",
                            "Article manquant");
            var orders = jdbc.queryForList("select * from orders order by id");
            var history = jdbc.queryForList("select * from order_fulfillment_events order by id");
            var checksums =
                    jdbc.queryForList(
                            "select id,md5sum from databasechangelog order by orderexecuted");
            if (version.equals("unknown")) {
                try (var resources = new ClassLoaderResourceAccessor()) {
                    assertThatThrownBy(() -> migration.migrate(db, resources))
                            .hasStackTraceContaining("ValidationFailedException");
                }
                assertThat(
                                jdbc.queryForObject(
                                        "select count(*) from databasechangelog where id='018-distribution-sequence'",
                                        Integer.class))
                        .isZero();
                assertThat(jdbc.queryForList("select * from order_fulfillment_events order by id"))
                        .isEqualTo(history);
            } else {
                try (var resources = new ClassLoaderResourceAccessor()) {
                    migration.migrate(db, resources);
                }
                var after = jdbc.queryForList("select * from order_fulfillment_events order by id");
                if (version.equals("original"))
                    after.forEach(row -> assertThat(row.remove("recording_sequence")).isNotNull());
                assertThat(after).isEqualTo(history);
                assertThat(
                                jdbc.queryForList(
                                        "select id from order_fulfillment_events order by recording_sequence",
                                        UUID.class))
                        .containsExactlyElementsOf(
                                version.equals("original")
                                        ? List.of(first, middle, last)
                                        : List.of(last, first, middle));
                assertThat(
                                jdbc.queryForList(
                                        "select indexdef from pg_indexes where indexname in ('ix_fulfillment_campaign','ix_fulfillment_order')",
                                        String.class))
                        .hasSize(2)
                        .allSatisfy(index -> assertThat(index).contains("recording_sequence"));
                try (var resources = new ClassLoaderResourceAccessor()) {
                    migration.migrate(db, resources);
                }
                assertThat(
                                jdbc.queryForObject(
                                        "select count(*) from databasechangelog where id='018-distribution-sequence'",
                                        Integer.class))
                        .isEqualTo(1);
                UUID pickup = append(jdbc, order, "PICKED_UP", "2026-10-03T12:00:04Z", null);
                assertThat(
                                jdbc.queryForObject(
                                        "select recording_sequence from order_fulfillment_events where id=?",
                                        Long.class,
                                        pickup))
                        .isEqualTo(4L);
                assertThatThrownBy(
                                () ->
                                        jdbc.update(
                                                "update order_fulfillment_events set note='rewrite' where id=?",
                                                first))
                        .isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(
                                () ->
                                        jdbc.update(
                                                "delete from order_fulfillment_events where id=?",
                                                first))
                        .isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(
                                () ->
                                        append(
                                                jdbc,
                                                order,
                                                "PICKED_UP",
                                                "2026-10-03T12:00:05Z",
                                                null))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
            assertThat(jdbc.queryForList("select * from orders order by id")).isEqualTo(orders);
            assertThat(
                            jdbc.queryForList(
                                    "select id,md5sum from databasechangelog where id not like '018-%' and id not like '019-%' and id not like '020-%' and id not like '021-%' and id not like '022-%' and id not like '023-%' and id not like '024-%' and id not like '025-%' and id not like '026-%' and id not like '027-%' and id not like '028-%' and id not like '029-%' order by orderexecuted"))
                    .isEqualTo(checksums);
        }
    }

    private UUID append(JdbcTemplate jdbc, UUID order, String type, String time, String note) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into order_fulfillment_events(id,organization_id,campaign_id,order_id,type,actor_id,recorded_at,note) select ?,organization_id,campaign_id,id,?,gen_random_uuid(),?::timestamptz,? from orders where id=?",
                id,
                type,
                time,
                note,
                order);
        return id;
    }

    private void prepare(String version) throws Exception {
        var source = Path.of("src/main/resources/db/changelog");
        var dest = Files.createDirectories(historical.resolve("db/changelog"));
        try (var paths = Files.list(source)) {
            for (var p : paths.toList()) Files.copy(p, dest.resolve(p.getFileName()));
        }
        String master =
                Files.readString(source.resolve("db.changelog-master.yaml")).replace("\r\n", "\n");
        Files.writeString(
                dest.resolve("db.changelog-master.yaml"),
                master.substring(0, master.indexOf("  - include:\n      file: 018-")));
        String sql =
                Files.readString(source.resolve("017-distribution.sql"))
                        .replace("\r\n", "\n")
                        .replace("--validCheckSum: " + WITH_SEQUENCE + "\n", "");
        if (version.equals("withSequence"))
            sql =
                    sql.replace(
                                    "    id uuid PRIMARY KEY,\n",
                                    "    id uuid PRIMARY KEY,\n    recording_sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,\n")
                            .replace(
                                    "campaign_id,recorded_at,id)",
                                    "campaign_id,recording_sequence)")
                            .replace("order_id,recorded_at,id)", "order_id,recording_sequence)");
        if (version.equals("unknown")) sql = sql.replace("note varchar(1000)", "note varchar(999)");
        Files.writeString(dest.resolve("017-distribution.sql"), sql);
    }
}
