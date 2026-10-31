package sn.groupia;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.UUID;
import liquibase.resource.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class RefreshSessionMigrationTests {
    @TempDir Path historical;

    @Test
    void upgrades027PreservingAccountsAndChecksums() throws Exception {
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
                master.substring(0, master.indexOf("  - include:\n      file: 028-")));
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
            j.update(
                    "insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at,version) values (?,'+221770001122',?,'ACTIVE',now(),now(),0)",
                    UUID.randomUUID(),
                    new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12)
                            .encode(UUID.randomUUID().toString()));
            // Compare the original columns explicitly: a newer migration can add
            // nullable profile columns without modifying any existing account data.
            var originalAccountColumns =
                    "id, phone_number, email, password_hash, status, created_at, updated_at, version";
            var accounts =
                    j.queryForList("select " + originalAccountColumns + " from user_accounts");
            var checksums = MigrationHistoryAssertions.snapshot(j);
            try (var resource = new ClassLoaderResourceAccessor()) {
                migrations.migrate(db, resource);
            }
            assertThat(j.queryForList("select " + originalAccountColumns + " from user_accounts"))
                    .isEqualTo(accounts);
            // An account created before V3 must not acquire fabricated personal data.
            assertThat(j.queryForMap("select first_name, last_name, birth_date from user_accounts"))
                    .containsEntry("first_name", null)
                    .containsEntry("last_name", null)
                    .containsEntry("birth_date", null);
            MigrationHistoryAssertions.assertPreserved(j, checksums);
            assertThat(j.queryForObject("select count(*) from auth_sessions", Integer.class))
                    .isZero();
            assertThat(j.queryForObject("select count(*) from auth_refresh_tokens", Integer.class))
                    .isZero();
        }
    }
}
