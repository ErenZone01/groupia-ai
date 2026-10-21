package sn.groupia;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class LogisticsMigrationUpgradeTests {
    @TempDir Path historical;

    @Test
    void existingDeparturesAndArrivalsSurviveAdditiveMigration() throws Exception {
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
                master.substring(0, master.indexOf("  - include:\n      file: 013-")));
        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            db.start();
            try (var resources = new DirectoryResourceAccessor(historical)) {
                migrate(db, resources);
            }
            var jdbc =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    db.getJdbcUrl(), db.getUsername(), db.getPassword()));
            UUID org = UUID.randomUUID();
            jdbc.update(
                    "insert into organizations(id,name,status,created_at,updated_at) values (?,'Migration','ACTIVE',now(),now())",
                    org);
            for (boolean arrived : List.of(false, true)) {
                UUID campaign = UUID.randomUUID();
                jdbc.update(
                        "insert into grouping_campaigns(id,organization_id,public_identifier,slug,title,status,currency,created_at,updated_at) values (?,?,?,'migration','Migration','DRAFT','XOF',now(),now())",
                        campaign,
                        org,
                        UUID.randomUUID());
                jdbc.update(
                        "insert into shipments(id,organization_id,grouping_campaign_id,transport_mode,departed_at,created_at,updated_at,tracking_reference,note,expected_arrival_at) values (?,?,?,'AIR',now()-interval '2 days',now()-interval '1 day',now()-interval '1 day','OLD-REF','OLD-NOTE',now()+interval '2 days')",
                        UUID.randomUUID(),
                        org,
                        campaign);
                if (arrived)
                    jdbc.update(
                            "update shipments set arrived_at=now(),updated_at=now() where grouping_campaign_id=?",
                            campaign);
            }
            var before =
                    jdbc.queryForList(
                            "select id,departed_at,arrived_at,created_at,updated_at,tracking_reference,note,expected_arrival_at from shipments order by id");
            var checksums = MigrationHistoryAssertions.snapshot(jdbc);
            try (var resources = new ClassLoaderResourceAccessor()) {
                migrate(db, resources);
            }
            assertThat(
                            jdbc.queryForList(
                                    "select id,departed_at,arrived_at,created_at,updated_at,tracking_reference,note,expected_arrival_at from shipments order by id"))
                    .isEqualTo(before);
            MigrationHistoryAssertions.assertPreserved(jdbc, checksums);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from shipment_facts where legacy and source='UNKNOWN'",
                                    Integer.class))
                    .isEqualTo(3);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from shipments where departure_confirmed_at=created_at and (arrived_at is null or arrival_confirmed_at=updated_at)",
                                    Integer.class))
                    .isEqualTo(2);
            jdbc.update(
                    "update shipments set provider_name='New information',version=version+1 where arrived_at is not null");
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "update shipments set departed_at=now(),version=version+1"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            try (var resources = new ClassLoaderResourceAccessor()) {
                migrate(db, resources);
            }
            assertThat(jdbc.queryForObject("select count(*) from shipment_facts", Integer.class))
                    .isEqualTo(3);
        }
    }

    private void migrate(PostgreSQLContainer db, ResourceAccessor resources) throws Exception {
        var database =
                DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(
                                new JdbcConnection(
                                        DriverManager.getConnection(
                                                db.getJdbcUrl(),
                                                db.getUsername(),
                                                db.getPassword())));
        try (var liquibase =
                new Liquibase("db/changelog/db.changelog-master.yaml", resources, database)) {
            liquibase.update(new Contexts(), new LabelExpression());
        }
    }
}
