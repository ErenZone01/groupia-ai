package sn.groupia;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.ZoneId;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.DirectoryResourceAccessor;
import liquibase.resource.ResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionMigrationTests {
    @TempDir Path historical;

    @Test
    void freshDatabaseInstallsSubscriptionSchema() throws Exception {
        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgres:latest"))) {
            db.start();
            try (var resources = new ClassLoaderResourceAccessor()) {
                migrate(db, resources);
            }
            var jdbc = jdbc(db);

            assertThat(jdbc.queryForObject(
                    "select count(*) from databasechangelog where id like '021-%'", Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("select count(*) from organization_subscriptions", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from subscription_billing_contacts", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from subscription_periods", Integer.class)).isZero();

            jdbc.update("update databasechangelog set md5sum=? where id='021-subscriptions'",
                    "9:1c621751968700b8e7bb2930af6c2eba");
            try (var resources = new ClassLoaderResourceAccessor()) {
                migrate(db, resources);
            }
            assertThat(jdbc.queryForObject(
                    "select md5sum from databasechangelog where id='021-subscriptions'", String.class))
                    .isEqualTo("9:1c621751968700b8e7bb2930af6c2eba");
        }
    }

    @Test
    void upgradeFrom020BackfillsLegacyTrialsAndReplaysIdempotently() throws Exception {
        var source = Path.of("src/main/resources/db/changelog");
        var destination = Files.createDirectories(historical.resolve("db/changelog"));
        try (var files = Files.list(source)) {
            for (var file : files.toList()) Files.copy(file, destination.resolve(file.getFileName()));
        }
        var master = Files.readString(source.resolve("db.changelog-master.yaml")).replace("\r\n", "\n");
        Files.writeString(destination.resolve("db.changelog-master.yaml"),
                master.substring(0, master.indexOf("  - include:\n      file: 021-")));

        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgres:latest"))) {
            db.start();
            try (var resources = new DirectoryResourceAccessor(historical)) {
                migrate(db, resources);
            }
            var jdbc = jdbc(db);
            UUID organizationId = UUID.randomUUID();
            UUID ownerId = UUID.randomUUID();
            jdbc.update("""
                    insert into organizations(id,name,status,created_at,updated_at)
                    values (?,?,'ACTIVE',current_timestamp - interval '2 years',current_timestamp - interval '2 years')
                    """, organizationId, "Boutique historique");
            jdbc.update("""
                    insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at)
                    values (?,?,'$2a$10$' || repeat('A',53),'ACTIVE',current_timestamp,current_timestamp)
                    """, ownerId, "+221770000001");
            jdbc.update("""
                    insert into organization_members(id,organization_id,user_account_id,role,status,joined_at)
                    values (?,?,?,'OWNER','ACTIVE',current_timestamp)
                    """, UUID.randomUUID(), organizationId, ownerId);
            var previousChecksums = jdbc.queryForList(
                    "select id,md5sum from databasechangelog order by orderexecuted");

            try (var resources = new ClassLoaderResourceAccessor()) {
                migrate(db, resources);
            }

            var cutover = jdbc.queryForObject(
                    "select applied_at from subscription_migration_metadata where id=1",
                    java.sql.Timestamp.class).toInstant();
            assertThat(jdbc.queryForObject("""
                    select trial_started_at from organization_subscriptions where organization_id=?
                    """, java.sql.Timestamp.class, organizationId).toInstant()).isEqualTo(cutover);
            assertThat(jdbc.queryForObject("""
                    select trial_ends_at from organization_subscriptions where organization_id=?
                    """, java.sql.Timestamp.class, organizationId).toInstant())
                    .isEqualTo(cutover.atZone(ZoneId.of("Africa/Dakar")).plusMonths(1).toInstant());
            assertThat(jdbc.queryForObject("""
                    select trial_provenance from organization_subscriptions where organization_id=?
                    """, String.class, organizationId)).isEqualTo("LEGACY_MIGRATION_TRIAL");
            assertThat(jdbc.queryForObject("""
                    select count(*) from subscription_billing_contacts where organization_id=?
                    """, Integer.class, organizationId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from subscription_periods", Integer.class)).isZero();

            var subscriptionsBeforeReplay = jdbc.queryForList(
                    "select * from organization_subscriptions order by organization_id");
            var contactsBeforeReplay = jdbc.queryForList(
                    "select * from subscription_billing_contacts order by organization_id");
            var migration = Files.readString(source.resolve("021-subscriptions.sql"));
            var backfill = migration.substring(migration.indexOf("INSERT INTO organization_subscriptions"),
                    migration.indexOf("--changeset groupia:021-subscription-period-history-guard"));
            jdbc.execute(backfill);
            assertThat(jdbc.queryForList("select * from organization_subscriptions order by organization_id"))
                    .isEqualTo(subscriptionsBeforeReplay);
            assertThat(jdbc.queryForList("select * from subscription_billing_contacts order by organization_id"))
                    .isEqualTo(contactsBeforeReplay);
            assertThat(jdbc.queryForList("""
                    select id,md5sum from databasechangelog where id not like '021-%' order by orderexecuted
                    """)).isEqualTo(previousChecksums);
        }
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer db) {
        return new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()));
    }

    private static void migrate(PostgreSQLContainer db, ResourceAccessor resources) throws Exception {
        var connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        var database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        try (var liquibase = new Liquibase("db/changelog/db.changelog-master.yaml", resources, database)) {
            liquibase.update(new Contexts(), new LabelExpression());
        }
    }
}
