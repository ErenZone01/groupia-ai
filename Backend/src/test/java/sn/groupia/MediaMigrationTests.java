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

class MediaMigrationTests {
    @TempDir Path historical;

    @Test
    void upgrades028WithoutChangingHistoricalChecksums() throws Exception {
        var source = Path.of("src/main/resources/db/changelog");
        var destination = Files.createDirectories(historical.resolve("db/changelog"));
        try (var files = Files.list(source)) {
            for (var file : files.toList())
                Files.copy(file, destination.resolve(file.getFileName()));
        }
        String master =
                Files.readString(source.resolve("db.changelog-master.yaml")).replace("\r\n", "\n");
        Files.writeString(
                destination.resolve("db.changelog-master.yaml"),
                master.substring(0, master.indexOf("  - include:\n      file: 029-")));
        var migrations = new DistributionMigrationTests();
        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            db.start();
            try (var resource = new DirectoryResourceAccessor(historical)) {
                migrations.migrate(db, resource);
            }
            var jdbc =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    db.getJdbcUrl(), db.getUsername(), db.getPassword()));
            var checksums =
                    jdbc.queryForList(
                            "select id,md5sum from databasechangelog order by orderexecuted");
            try (var resource = new ClassLoaderResourceAccessor()) {
                migrations.migrate(db, resource);
            }
            assertThat(
                            jdbc.queryForList(
                                    "select id,md5sum from databasechangelog where id not like '029-%' order by orderexecuted"))
                    .isEqualTo(checksums);
            assertThat(jdbc.queryForObject("select count(*) from product_media", Integer.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from product_media_galleries", Integer.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select condeferrable from pg_constraint where conname='uk_media_position'",
                                    Boolean.class))
                    .isTrue();
        }
    }
}
