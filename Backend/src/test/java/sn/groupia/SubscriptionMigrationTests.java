package sn.groupia;

import static org.assertj.core.api.Assertions.assertThat;

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

class SubscriptionMigrationTests {
    @TempDir Path historical;

    @Test
    void freshDatabaseInstallsSubscriptionSchema() throws Exception {
        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            db.start();
            try (var resources = new ClassLoaderResourceAccessor()) {
                migrate(db, resources);
            }
            var jdbc = jdbc(db);

            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from databasechangelog where id like '021-%'",
                                    Integer.class))
                    .isEqualTo(3);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from databasechangelog where id like '022-%'",
                                    Integer.class))
                    .isEqualTo(3);
            assertThat(
                            jdbc.queryForObject(
                                    "select md5sum from databasechangelog where id='021-subscriptions'",
                                    String.class))
                    .isEqualTo("9:1c621751968700b8e7bb2930af6c2eba");
            assertThat(
                            jdbc.queryForObject(
                                    "select md5sum from databasechangelog where id='022-subscription-migration-metadata'",
                                    String.class))
                    .isEqualTo("9:eacae86d733433f7250342b22285a176");
            assertThat(
                            jdbc.queryForObject(
                                    "select md5sum from databasechangelog where id='022-subscription-backfill'",
                                    String.class))
                    .isEqualTo("9:b6513b428b9baa70d24ac334e178dfc0");
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from organization_subscriptions",
                                    Integer.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from subscription_billing_contacts",
                                    Integer.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from subscription_periods", Integer.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from subscription_migration_metadata",
                                    Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void upgradeFrom020BackfillsLegacyTrialsAndReplaysIdempotently() throws Exception {
        var source = Path.of("src/main/resources/db/changelog");
        var destination = copyChangelog(historical.resolve("legacy/db/changelog"));
        var master =
                Files.readString(source.resolve("db.changelog-master.yaml")).replace("\r\n", "\n");
        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            db.start();
            Files.writeString(
                    destination.resolve("db.changelog-master.yaml"),
                    master.substring(0, master.indexOf("  - include:\n      file: 021-")));
            migrate(db, new DirectoryResourceAccessor(historical.resolve("legacy")));
            var jdbc = jdbc(db);
            UUID organizationId = UUID.randomUUID();
            UUID ownerId = UUID.randomUUID();
            jdbc.update(
                    """
                    insert into organizations(id,name,status,created_at,updated_at)
                    values (?,?,'ACTIVE',current_timestamp - interval '2 years',current_timestamp - interval '2 years')
                    """,
                    organizationId,
                    "Boutique historique");
            jdbc.update(
                    """
                    insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at)
                    values (?,?,'$2a$10$' || repeat('A',53),'ACTIVE',current_timestamp,current_timestamp)
                    """,
                    ownerId,
                    "+221770000001");
            jdbc.update(
                    """
                    insert into organization_members(id,organization_id,user_account_id,role,status,joined_at)
                    values (?,?,?,'OWNER','ACTIVE',current_timestamp)
                    """,
                    UUID.randomUUID(),
                    organizationId,
                    ownerId);
            var previousChecksums =
                    jdbc.queryForList(
                            "select id,md5sum from databasechangelog order by orderexecuted");

            Files.writeString(
                    destination.resolve("db.changelog-master.yaml"),
                    master.substring(0, master.indexOf("  - include:\n      file: 022-")));
            migrate(db, new DirectoryResourceAccessor(historical.resolve("legacy")));
            assertThat(
                            jdbc.queryForObject(
                                    "select md5sum from databasechangelog where id='021-subscriptions'",
                                    String.class))
                    .isEqualTo("9:1c621751968700b8e7bb2930af6c2eba");
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from information_schema.tables where table_schema='public' "
                                            + "and table_name='subscription_migration_metadata'",
                                    Integer.class))
                    .isZero();
            var subscriptionsBefore022 =
                    jdbc.queryForList(
                            "select * from organization_subscriptions order by organization_id");
            var contactsBefore022 =
                    jdbc.queryForList(
                            "select * from subscription_billing_contacts order by organization_id");

            Files.writeString(destination.resolve("db.changelog-master.yaml"), master);
            migrate(db, new DirectoryResourceAccessor(historical.resolve("legacy")));
            assertThat(
                            jdbc.queryForList(
                                    "select * from organization_subscriptions order by organization_id"))
                    .isEqualTo(subscriptionsBefore022);
            assertThat(
                            jdbc.queryForList(
                                    "select * from subscription_billing_contacts order by organization_id"))
                    .isEqualTo(contactsBefore022);

            var cutover =
                    jdbc.queryForObject(
                                    "select applied_at from subscription_migration_metadata where id=1",
                                    java.sql.Timestamp.class)
                            .toInstant();
            assertThat(
                            jdbc.queryForObject(
                                    "select md5sum from databasechangelog where id='021-subscriptions'",
                                    String.class))
                    .isEqualTo("9:1c621751968700b8e7bb2930af6c2eba");
            assertThat(
                            jdbc.queryForObject(
                                            """
                    select trial_started_at from organization_subscriptions where organization_id=?
                    """,
                                            java.sql.Timestamp.class,
                                            organizationId)
                                    .toInstant())
                    .isEqualTo(cutover);
            assertThat(
                            jdbc.queryForObject(
                                            """
                    select trial_ends_at from organization_subscriptions where organization_id=?
                    """,
                                            java.sql.Timestamp.class,
                                            organizationId)
                                    .toInstant())
                    .isEqualTo(cutover.atZone(ZoneId.of("Africa/Dakar")).plusMonths(1).toInstant());
            assertThat(
                            jdbc.queryForObject(
                                    """
                    select trial_provenance from organization_subscriptions where organization_id=?
                    """,
                                    String.class,
                                    organizationId))
                    .isEqualTo("LEGACY_MIGRATION_TRIAL");
            assertThat(
                            jdbc.queryForObject(
                                    """
                    select count(*) from subscription_billing_contacts where organization_id=?
                    """,
                                    Integer.class,
                                    organizationId))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from subscription_periods", Integer.class))
                    .isZero();

            var subscriptionsBeforeReplay =
                    jdbc.queryForList(
                            "select * from organization_subscriptions order by organization_id");
            var contactsBeforeReplay =
                    jdbc.queryForList(
                            "select * from subscription_billing_contacts order by organization_id");
            replayBackfill(jdbc, source.resolve("022-subscription-compatibility.sql"));
            assertThat(
                            jdbc.queryForList(
                                    "select * from organization_subscriptions order by organization_id"))
                    .isEqualTo(subscriptionsBeforeReplay);
            assertThat(
                            jdbc.queryForList(
                                    "select * from subscription_billing_contacts order by organization_id"))
                    .isEqualTo(contactsBeforeReplay);
            assertThat(
                            jdbc.queryForList(
                                    """
                    select id,md5sum from databasechangelog where id not like '021-%' and id not like '022-%' and id not like '023-%' and id not like '024-%' and id not like '025-%'
                    order by orderexecuted
                    """))
                    .isEqualTo(previousChecksums);
        }
    }

    @Test
    void freshAndHistorical021PathsConvergeAfter022() throws Exception {
        var source = Path.of("src/main/resources/db/changelog");
        var master =
                Files.readString(source.resolve("db.changelog-master.yaml")).replace("\r\n", "\n");
        var legacyRoot = historical.resolve("legacy-path");
        var freshRoot = historical.resolve("fresh-path");
        var legacyChangelog = copyChangelog(legacyRoot.resolve("db/changelog"));
        var freshChangelog = copyChangelog(freshRoot.resolve("db/changelog"));
        var organizationId = UUID.fromString("a43e4b27-fb38-4c65-a2aa-07a086ac9b01");
        var ownerId = UUID.fromString("b43e4b27-fb38-4c65-a2aa-07a086ac9b02");
        var oldMaster = master.substring(0, master.indexOf("  - include:\n      file: 021-"));

        try (var legacyDb = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));
                var freshDb = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            legacyDb.start();
            freshDb.start();
            Files.writeString(legacyChangelog.resolve("db.changelog-master.yaml"), oldMaster);
            Files.writeString(freshChangelog.resolve("db.changelog-master.yaml"), oldMaster);
            migrate(legacyDb, new DirectoryResourceAccessor(legacyRoot));
            migrate(freshDb, new DirectoryResourceAccessor(freshRoot));
            seedLegacyOrganization(jdbc(legacyDb), organizationId, ownerId);
            seedLegacyOrganization(jdbc(freshDb), organizationId, ownerId);

            Files.writeString(
                    legacyChangelog.resolve("db.changelog-master.yaml"),
                    master.substring(0, master.indexOf("  - include:\n      file: 022-")));
            migrate(legacyDb, new DirectoryResourceAccessor(legacyRoot));
            assertThat(
                            jdbc(legacyDb)
                                    .queryForObject(
                                            "select md5sum from databasechangelog where id='021-subscriptions'",
                                            String.class))
                    .isEqualTo("9:1c621751968700b8e7bb2930af6c2eba");

            Files.writeString(legacyChangelog.resolve("db.changelog-master.yaml"), master);
            Files.writeString(freshChangelog.resolve("db.changelog-master.yaml"), master);
            migrate(legacyDb, new DirectoryResourceAccessor(legacyRoot));
            migrate(freshDb, new DirectoryResourceAccessor(freshRoot));

            var legacyJdbc = jdbc(legacyDb);
            var freshJdbc = jdbc(freshDb);
            assertThat(subscriptionSchema(legacyJdbc)).isEqualTo(subscriptionSchema(freshJdbc));
            assertLegacyBackfillInvariants(legacyJdbc, organizationId, ownerId);
            assertLegacyBackfillInvariants(freshJdbc, organizationId, ownerId);
        }
    }

    private static Path copyChangelog(Path destination) throws Exception {
        var source = Path.of("src/main/resources/db/changelog");
        Files.createDirectories(destination);
        try (var files = Files.list(source)) {
            for (var file : files.toList())
                Files.copy(file, destination.resolve(file.getFileName()));
        }
        return destination;
    }

    private static void seedLegacyOrganization(
            JdbcTemplate jdbc, UUID organizationId, UUID ownerId) {
        jdbc.update(
                """
                insert into organizations(id,name,status,created_at,updated_at)
                values (?,?,'ACTIVE',current_timestamp - interval '2 years',current_timestamp - interval '2 years')
                """,
                organizationId,
                "Boutique historique");
        jdbc.update(
                """
                insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at)
                values (?,?,'$2a$10$' || repeat('A',53),'ACTIVE',current_timestamp,current_timestamp)
                """,
                ownerId,
                "+221770000001");
        jdbc.update(
                """
                insert into organization_members(id,organization_id,user_account_id,role,status,joined_at)
                values (?,?,?,'OWNER','ACTIVE',current_timestamp)
                """,
                UUID.randomUUID(),
                organizationId,
                ownerId);
    }

    private static void assertLegacyBackfillInvariants(
            JdbcTemplate jdbc, UUID organizationId, UUID ownerId) {
        var cutover =
                jdbc.queryForObject(
                                "select applied_at from subscription_migration_metadata where id=1",
                                java.sql.Timestamp.class)
                        .toInstant();
        var trialStart =
                jdbc.queryForObject(
                                """
                select trial_started_at from organization_subscriptions where organization_id=?
                """,
                                java.sql.Timestamp.class,
                                organizationId)
                        .toInstant();
        var trialEnd =
                jdbc.queryForObject(
                                """
                select trial_ends_at from organization_subscriptions where organization_id=?
                """,
                                java.sql.Timestamp.class,
                                organizationId)
                        .toInstant();
        assertThat(trialStart).isEqualTo(cutover);
        assertThat(trialEnd)
                .isEqualTo(trialStart.atZone(ZoneId.of("Africa/Dakar")).plusMonths(1).toInstant());
        assertThat(
                        jdbc.queryForObject(
                                """
                select trial_provenance from organization_subscriptions where organization_id=?
                """,
                                String.class,
                                organizationId))
                .isEqualTo("LEGACY_MIGRATION_TRIAL");
        assertThat(
                        jdbc.queryForObject(
                                """
                select updated_by from subscription_billing_contacts where organization_id=?
                """,
                                UUID.class,
                                organizationId))
                .isEqualTo(ownerId);
        assertThat(
                        jdbc.queryForObject(
                                """
                select count(*) from pg_constraint
                where conrelid='subscription_periods'::regclass and contype='c'
                  and position('paid_at' in pg_get_constraintdef(oid)) > 0
                  and position('created_at' in pg_get_constraintdef(oid)) > 0
                """,
                                Integer.class))
                .isZero();
    }

    private static java.util.Map<String, java.util.List<java.util.Map<String, Object>>>
            subscriptionSchema(JdbcTemplate jdbc) {
        var tables =
                "'organization_subscriptions','subscription_billing_contacts','subscription_periods',"
                        + "'subscription_migration_metadata'";
        return java.util.Map.of(
                "columns",
                        jdbc.queryForList(
                                """
                        select table_name,column_name,ordinal_position,data_type,udt_name,is_nullable,column_default
                        from information_schema.columns
                        where table_schema='public' and table_name in (%s)
                        order by table_name,ordinal_position
                        """
                                        .formatted(tables)),
                "constraints",
                        jdbc.queryForList(
                                """
                        select table_name,constraint_name,constraint_type
                        from information_schema.table_constraints
                        where table_schema='public' and table_name in (%s)
                        order by table_name,constraint_name
                        """
                                        .formatted(tables)),
                "checks",
                        jdbc.queryForList(
                                """
                        select relation.relname as table_name, constraint_row.conname,
                               pg_get_constraintdef(constraint_row.oid) as definition
                        from pg_constraint constraint_row
                        join pg_class relation on relation.oid=constraint_row.conrelid
                        join pg_namespace schema_row on schema_row.oid=relation.relnamespace
                        where schema_row.nspname='public' and relation.relname in (%s)
                        order by relation.relname,constraint_row.conname
                        """
                                        .formatted(tables)),
                "indexes",
                        jdbc.queryForList(
                                """
                        select tablename,indexname,indexdef from pg_indexes
                        where schemaname='public' and tablename in (%s)
                        order by tablename,indexname
                        """
                                        .formatted(tables)),
                "functions",
                        jdbc.queryForList(
                                """
                        select routine_name,routine_definition
                        from information_schema.routines
                        where routine_schema='public' and routine_name='guard_subscription_period_history'
                        order by routine_name
                        """),
                "triggers",
                        jdbc.queryForList(
                                """
                        select event_object_table,trigger_name,event_manipulation,action_timing,action_statement
                        from information_schema.triggers
                        where trigger_schema='public' and event_object_table in (%s)
                        order by event_object_table,trigger_name,event_manipulation
                        """
                                        .formatted(tables)));
    }

    private static void replayBackfill(JdbcTemplate jdbc, Path migration) throws Exception {
        var sql = Files.readString(migration);
        var start = sql.indexOf("--changeset groupia:022-subscription-backfill");
        assertThat(start).isGreaterThanOrEqualTo(0);
        var statements = sql.substring(start).replaceFirst("(?s)^.*?\\n", "");
        for (var statement : statements.split(";")) {
            if (!statement.isBlank()) jdbc.execute(statement);
        }
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer db) {
        return new JdbcTemplate(
                new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()));
    }

    private static void migrate(PostgreSQLContainer db, ResourceAccessor resources)
            throws Exception {
        var connection =
                DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        var database =
                DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        try (var liquibase =
                new Liquibase("db/changelog/db.changelog-master.yaml", resources, database)) {
            liquibase.update(new Contexts(), new LabelExpression());
        }
    }
}
