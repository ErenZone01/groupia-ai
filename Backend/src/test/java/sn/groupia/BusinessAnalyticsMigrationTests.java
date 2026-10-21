package sn.groupia;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class BusinessAnalyticsMigrationTests {
    @TempDir Path historical;

    @Test
    void upgrades029WithOnlyAnAdditiveUsableIndexAndPreservesHistory() throws Exception {
        var source = Path.of("src/main/resources/db/changelog");
        var destination = Files.createDirectories(historical.resolve("db/changelog"));
        try (var files = Files.list(source)) {
            for (var file : files.toList())
                Files.copy(file, destination.resolve(file.getFileName()));
        }
        String master =
                Files.readString(source.resolve("db.changelog-master.yaml")).replace("\r\n", "\n");
        int nextInclude = master.indexOf("  - include:", master.indexOf("file: 030-"));
        String through030 = nextInclude < 0 ? master : master.substring(0, nextInclude);
        Files.writeString(
                destination.resolve("db.changelog-master.yaml"),
                master.substring(0, master.indexOf("  - include:\n      file: 030-")));
        var migrations = new DistributionMigrationTests();
        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            db.start();
            try (var resources = new DirectoryResourceAccessor(historical)) {
                migrations.migrate(db, resources);
            }
            var jdbc =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    db.getJdbcUrl(), db.getUsername(), db.getPassword()));
            var order = migrations.seedOrder(jdbc);
            jdbc.update(
                    "update orders set status='CONFIRMED',confirmed_at=now() where id=?", order);
            var history = MigrationHistoryAssertions.snapshot(jdbc);
            var orders = jdbc.queryForList("select * from orders order by id");
            var customers = jdbc.queryForList("select * from customers order by id");
            var columns =
                    jdbc.queryForList(
                            "select table_name,column_name,data_type,is_nullable,column_default from information_schema.columns where table_schema='public' order by table_name,ordinal_position");
            var constraints =
                    jdbc.queryForList(
                            "select conname,pg_get_constraintdef(oid) as definition from pg_constraint where connamespace='public'::regnamespace order by conrelid,conname");
            Files.writeString(destination.resolve("db.changelog-master.yaml"), through030);
            try (var resources = new DirectoryResourceAccessor(historical)) {
                migrations.migrate(db, resources);
            }
            MigrationHistoryAssertions.assertPreserved(jdbc, history);
            assertThat(jdbc.queryForList("select * from orders order by id")).isEqualTo(orders);
            assertThat(jdbc.queryForList("select * from customers order by id"))
                    .isEqualTo(customers);
            assertThat(
                            jdbc.queryForList(
                                    "select table_name,column_name,data_type,is_nullable,column_default from information_schema.columns where table_schema='public' order by table_name,ordinal_position"))
                    .isEqualTo(columns);
            assertThat(
                            jdbc.queryForList(
                                    "select conname,pg_get_constraintdef(oid) as definition from pg_constraint where connamespace='public'::regnamespace order by conrelid,conname"))
                    .isEqualTo(constraints);
            assertThat(
                            jdbc.queryForObject(
                                    "select indexdef from pg_indexes where schemaname='public' and indexname='ix_orders_business_analytics'",
                                    String.class))
                    .contains(
                            "(organization_id, confirmed_at)",
                            "CONFIRMED",
                            "COMPLETED",
                            "XOF",
                            "confirmed_at IS NOT NULL");
            // Populate older eligible orders so date selectivity distinguishes this index
            // from the existing tenant/status indexes. Do not force planner settings.
            jdbc.update(
                    """
                    insert into orders
                    select (jsonb_populate_record(null::orders, to_jsonb(seed) || jsonb_build_object(
                        'id', gen_random_uuid(),
                        'order_number', 'GRP-' || lpad(n::text,12,'0'),
                        'checkout_idempotency_key', 'analytics-fixture-' || n,
                        'tracking_token_hash', lpad(to_hex(n),64,'0'),
                        'placed_at', now()-interval '5 years',
                        'confirmed_at', now()-interval '5 years'))).*
                    from orders seed cross join generate_series(1000,2999) series(n)
                    where seed.id=?
                    """,
                    order);
            jdbc.execute("analyze orders");
            try (var connection = jdbc.getDataSource().getConnection();
                    var statement = connection.createStatement()) {
                try (var plan =
                        statement.executeQuery(
                                "explain (costs off) select count(*),sum(goods_subtotal) from orders where organization_id='"
                                        + orders.getFirst().get("organization_id")
                                        + "' and currency='XOF' and status in ('CONFIRMED','COMPLETED') and confirmed_at is not null and confirmed_at >= now()-interval '12 months' and confirmed_at < now()+interval '1 day'")) {
                    var text = new StringBuilder();
                    while (plan.next()) text.append(plan.getString(1));
                    assertThat(text.toString()).contains("ix_orders_business_analytics");
                }
            }
            var upgraded = MigrationHistoryAssertions.snapshot(jdbc);
            try (var resources = new DirectoryResourceAccessor(historical)) {
                migrations.migrate(db, resources);
            }
            assertThat(MigrationHistoryAssertions.snapshot(jdbc)).isEqualTo(upgraded);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from databasechangelog where id='030-business-analytics-index'",
                                    Integer.class))
                    .isEqualTo(1);
        }
    }
}
