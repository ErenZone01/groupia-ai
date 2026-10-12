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

class ShippingMigrationUpgradeTests {
    @TempDir Path historical;

    @Test
    void upgradeFrom013PreservesOrdersTermsAndShipmentHistory() throws Exception {
        var source = Path.of("src/main/resources/db/changelog");
        var dest = Files.createDirectories(historical.resolve("db/changelog"));
        try (var paths = Files.list(source)) {
            for (var p : paths.toList()) Files.copy(p, dest.resolve(p.getFileName()));
        }
        var master =
                Files.readString(source.resolve("db.changelog-master.yaml")).replace("\r\n", "\n");
        Files.writeString(
                dest.resolve("db.changelog-master.yaml"),
                master.substring(0, master.indexOf("  - include:\n      file: 014-")));
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
            var checksums =
                    jdbc.queryForList(
                            "select id,md5sum from databasechangelog order by orderexecuted");
            UUID campaign =
                    jdbc.queryForObject(
                            "select campaign_id from orders where id=?", UUID.class, order);
            UUID org =
                    jdbc.queryForObject(
                            "select organization_id from orders where id=?", UUID.class, order);
            UUID shipment = UUID.randomUUID();
            jdbc.update(
                    "insert into shipments(id,organization_id,grouping_campaign_id,transport_mode,created_at,updated_at,version) values (?,?,?,'AIR',now(),now(),0)",
                    shipment,
                    org,
                    campaign);
            jdbc.update(
                    "insert into shipment_facts(id,organization_id,shipment_id,shipment_version,type,recorded_at,source,actor_id) values (?,?,?,0,'DOSSIER_UPDATED',now(),'FORWARDER',?)",
                    UUID.randomUUID(),
                    org,
                    shipment,
                    UUID.randomUUID());
            var facts = jdbc.queryForList("select * from shipment_facts");
            var shipments = jdbc.queryForList("select * from shipments");
            try (var resources = new ClassLoaderResourceAccessor()) {
                migrate(db, resources);
            }
            var upgraded = jdbc.queryForMap("select * from orders where id=?", order);
            assertThat(upgraded.remove("ready_for_pickup_at")).isNull();
            assertThat(upgraded).isEqualTo(before);
            assertThat(jdbc.queryForList("select * from shipment_facts")).isEqualTo(facts);
            assertThat(jdbc.queryForList("select * from shipments")).isEqualTo(shipments);
            assertThat(
                            jdbc.queryForList(
                                    "select id,md5sum from databasechangelog where id not like '014-%' and id not like '015-%' and id not like '016-%' and id not like '017-%' and id not like '018-%' and id not like '019-%' and id not like '020-%' and id not like '021-%' and id not like '022-%' and id not like '023-%' and id not like '024-%' and id not like '025-%' order by orderexecuted"))
                    .isEqualTo(checksums);
            assertThat(
                            jdbc.queryForObject(
                                    "select volumetric_divisor from grouping_campaigns where id=?",
                                    java.math.BigDecimal.class,
                                    campaign))
                    .isNull();
            for (String table :
                    List.of(
                            "shipping_assessments",
                            "shipping_measurements",
                            "shipping_calculations"))
                assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class))
                        .isZero();
            try (var resources = new ClassLoaderResourceAccessor()) {
                migrate(db, resources);
            }
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

    private UUID seedOrder(JdbcTemplate jdbc) {
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
