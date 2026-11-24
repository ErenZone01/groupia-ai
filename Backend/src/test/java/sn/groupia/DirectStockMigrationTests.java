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

class DirectStockMigrationTests {
    @TempDir Path historical;

    @Test
    void upgradeDoesNotInventStockOrRewriteExistingOrdersAndPayments() throws Exception {
        var source = Path.of("src/main/resources/db/changelog");
        var dest = Files.createDirectories(historical.resolve("db/changelog"));
        try (var files = Files.list(source)) {
            for (var file : files.toList()) Files.copy(file, dest.resolve(file.getFileName()));
        }
        String master =
                Files.readString(source.resolve("db.changelog-master.yaml")).replace("\r\n", "\n");
        Files.writeString(
                dest.resolve("db.changelog-master.yaml"),
                master.substring(0, master.indexOf("  - include:\n      file: 065-")));
        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            db.start();
            var migrations = new DistributionMigrationTests();
            try (var resources = new DirectoryResourceAccessor(historical)) {
                migrations.migrate(db, resources);
            }
            var j =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    db.getJdbcUrl(), db.getUsername(), db.getPassword()));
            var f = MerchantSettlementFixtures.seed(j);
            MerchantSettlementFixtures.settled(j, f, "INITIAL_ORDER");
            UUID product = UUID.randomUUID(),
                    variant = UUID.randomUUID(),
                    store = UUID.randomUUID(),
                    offer = UUID.randomUUID();
            j.update(
                    "insert into products(id,organization_id,name,status,created_at,updated_at) values (?,?,'Historical','ACTIVE',now(),now())",
                    product,
                    f.org());
            j.update(
                    """
                insert into product_variants(id,organization_id,product_id,attributes,canonical_variant_key,sale_unit,active,created_at,updated_at)
                values (?,?,?,'{}','DEFAULT','UNIT',true,now(),now())
                """,
                    variant,
                    f.org(),
                    product);
            j.update(
                    "insert into direct_storefronts(id,organization_id,public_identifier,display_name,created_at,updated_at) values (?,?,?,'Historical',now(),now())",
                    store,
                    f.org(),
                    UUID.randomUUID());
            j.update(
                    """
                insert into direct_offers(id,organization_id,storefront_id,product_variant_id,price_xof,published,version,created_at,updated_at)
                values (?,?,?,?,1000,true,7,now(),now())
                """,
                    offer,
                    f.org(),
                    store,
                    variant);
            var payments = j.queryForList("select * from payments order by id");
            var orders = j.queryForList("select * from orders order by id");
            var offers = j.queryForList("select * from direct_offers order by id");
            var history = MigrationHistoryAssertions.snapshot(j);
            try (var resources = new ClassLoaderResourceAccessor()) {
                migrations.migrate(db, resources);
                migrations.migrate(db, resources);
            }
            var migrated = j.queryForList("select * from direct_offers order by id");
            for (var row : migrated) {
                assertThat(row.remove("stock_limit")).isNull();
                assertThat(row.remove("stock_committed")).isEqualTo(0L);
                assertThat(row.remove("stock_version")).isEqualTo(0L);
            }
            assertThat(migrated).isEqualTo(offers);
            assertThat(j.queryForList("select * from payments order by id")).isEqualTo(payments);
            assertThat(j.queryForList("select * from orders order by id")).isEqualTo(orders);
            MigrationHistoryAssertions.assertPreserved(j, history);
            assertThatThrownBy(
                            () ->
                                    j.update(
                                            "update direct_offers set stock_limit=0,stock_committed=1"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }
}
