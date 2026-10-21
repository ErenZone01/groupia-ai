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

class DistributionMigrationTests {
    @TempDir Path historical;

    @Test
    void upgradeFrom016PreservesEveryHistoricalColumnAndAddsAppendOnlyHistory() throws Exception {
        var source = Path.of("src/main/resources/db/changelog");
        var dest = Files.createDirectories(historical.resolve("db/changelog"));
        try (var paths = Files.list(source)) {
            for (var p : paths.toList()) Files.copy(p, dest.resolve(p.getFileName()));
        }
        var master =
                Files.readString(source.resolve("db.changelog-master.yaml")).replace("\r\n", "\n");
        Files.writeString(
                dest.resolve("db.changelog-master.yaml"),
                master.substring(0, master.indexOf("  - include:\n      file: 017-")));
        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            db.start();
            try (var resources = new DirectoryResourceAccessor(historical)) {
                migrate(db, resources);
            }
            var jdbc =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    db.getJdbcUrl(), db.getUsername(), db.getPassword()));
            UUID order = seedOrder(jdbc);
            var before = jdbc.queryForMap("select * from orders where id=?", order);
            var checksums = MigrationHistoryAssertions.snapshot(jdbc);
            try (var resources = new ClassLoaderResourceAccessor()) {
                migrate(db, resources);
            }
            var upgraded = jdbc.queryForMap("select * from orders where id=?", order);
            assertThat(upgraded.remove("ready_for_pickup_at")).isNull();
            assertThat(upgraded).isEqualTo(before);
            MigrationHistoryAssertions.assertPreserved(jdbc, checksums);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from order_fulfillment_events", Integer.class))
                    .isZero();
            String insert =
                    "insert into order_fulfillment_events(id,organization_id,campaign_id,order_id,type,actor_id,recorded_at,note) select gen_random_uuid(),organization_id,campaign_id,id,?,gen_random_uuid(),now(),? from orders where id=?";
            assertThatThrownBy(() -> jdbc.update(insert, "READINESS_REVOKED", " ", order))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            jdbc.update(insert, "READY_FOR_PICKUP", null, order);
            jdbc.update(insert, "READINESS_REVOKED", "Erreur", order);
            jdbc.update(insert, "READY_FOR_PICKUP", null, order);
            jdbc.update(insert, "PICKED_UP", null, order);
            assertThatThrownBy(() -> jdbc.update(insert, "PICKED_UP", null, order))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(
                            () -> jdbc.update("update order_fulfillment_events set note='rewrite'"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("delete from order_fulfillment_events"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "insert into order_fulfillment_events(id,organization_id,campaign_id,order_id,type,actor_id,recorded_at) select gen_random_uuid(),gen_random_uuid(),campaign_id,id,'READY_FOR_PICKUP',gen_random_uuid(),now() from orders where id=?",
                                            order))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            try (var resources = new ClassLoaderResourceAccessor()) {
                migrate(db, resources);
            }
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from order_fulfillment_events", Integer.class))
                    .isEqualTo(4);
        }
    }

    void migrate(PostgreSQLContainer db, ResourceAccessor resources) throws Exception {
        var database =
                DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(
                                new JdbcConnection(
                                        DriverManager.getConnection(
                                                db.getJdbcUrl(),
                                                db.getUsername(),
                                                db.getPassword())));
        try (var l = new Liquibase("db/changelog/db.changelog-master.yaml", resources, database)) {
            l.update(new Contexts(), new LabelExpression());
        }
    }

    UUID seedOrder(JdbcTemplate jdbc) {
        UUID organization = UUID.randomUUID(),
                campaign = UUID.randomUUID(),
                customer = UUID.randomUUID(),
                order = UUID.randomUUID();
        jdbc.update(
                "insert into organizations(id,name,status,created_at,updated_at) values (?,'Migration fixture','ACTIVE',now(),now())",
                organization);
        jdbc.update(
                "insert into grouping_campaigns(id,organization_id,public_identifier,slug,title,status,currency,created_at,updated_at) values (?,?,?,'migration','Migration','DRAFT','XOF',now(),now())",
                campaign,
                organization,
                UUID.randomUUID());
        jdbc.update(
                "insert into customers(id,organization_id,first_name,last_name,phone_e164,created_at,updated_at) values (?,?,'Migration','Fixture','+221771234567',now(),now())",
                customer,
                organization);
        jdbc.update(
                """
            insert into orders(id,organization_id,campaign_id,customer_id,order_number,status,fulfillment_status,currency,
                customer_contact_snapshot,goods_subtotal,platform_fee_amount,platform_fee_rule_snapshot,shipping_terms_snapshot,
                initial_shipping_charge_amount,initial_amount_due,checkout_idempotency_key,checkout_request_fingerprint,
                tracking_token_hash,payment_deadline,tracking_token_expires_at,placed_at)
            values (?,?,?,?,'GRP-000000000001','PENDING_PAYMENT','NOT_READY','XOF',
                '{"firstName":"Migration","lastName":"Fixture","phoneE164":"+221771234567"}',1000,300,
                '{"type":"FIXED","currency":"XOF","amount":300,"version":1}',
                '{"shippingCollectionMode":"AFTER_FINAL_ASSESSMENT","shippingPricingMethod":"FIXED","shippingRate":0,"shippingCurrency":"XOF","roundingRule":"HALF_UP"}',
                0,1300,'migration-test-key',repeat('a',64),repeat('b',64),now()+interval '30 minutes',now()+interval '180 days',now())
            """,
                order,
                organization,
                campaign,
                customer);
        return order;
    }
}
