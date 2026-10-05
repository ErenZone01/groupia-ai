package sn.groupia;

import java.nio.file.*;
import java.sql.DriverManager;
import java.util.UUID;
import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

class OrderingMigrationUpgradeTests {
    private static final String MASTER="db/changelog/db.changelog-master.yaml";
    private static final String LEGACY_CHECKSUM="9:aa0b0f88a758f6300ff30ca7ca1365aa";
    private static final String CURRENT_CHECKSUM="9:e0bde6c3e9dc14001d170e1204b06a7b";
    @TempDir Path historicalResources;

    @ParameterizedTest
    @ValueSource(strings={"legacy006","current006","current007"})
    void upgradesExistingSchemaPreservingOrderAndHistoricalChecksum(String version) throws Exception {
        prepareHistoricalResources(version);
        try(var postgres=new PostgreSQLContainer(DockerImageName.parse("postgres:latest"))) {
            postgres.start();
            var jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
            try(var resources=new DirectoryResourceAccessor(historicalResources)) { update(postgres,resources); }
            String checksum=version.equals("legacy006")?LEGACY_CHECKSUM:CURRENT_CHECKSUM;
            assertThat(jdbc.queryForObject("select md5sum from databasechangelog where id='006-ordering'",String.class)).isEqualTo(checksum);
            var order=seedOrder(jdbc);
            String before=jdbc.queryForObject("select (to_jsonb(o)-'ready_for_pickup_at')::text from orders o where id=?",String.class,order);
            try(var resources=new ClassLoaderResourceAccessor()) { update(postgres,resources); }
            assertThat(jdbc.queryForObject("select (to_jsonb(o)-'ready_for_pickup_at')::text from orders o where id=?",String.class,order)).isEqualTo(before);
            assertThat(jdbc.queryForObject("select md5sum from databasechangelog where id='006-ordering'",String.class)).isEqualTo(checksum);
            assertThat(jdbc.queryForObject("select count(*) from databasechangelog where id='008-ordering-constraint-upgrade'",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from payments",Integer.class)).isZero();
            if(!version.equals("current007")) {
                assertThat(jdbc.queryForObject("select expected_amount from payment_requests where order_id=?",Integer.class,order)).isEqualTo(1300);
            }
            assertThat(jdbc.update("update orders set status='EXPIRED',fulfillment_status='CANCELLED' where id=?",order)).isEqualTo(1);
            assertThatThrownBy(()->jdbc.update("update orders set customer_contact_snapshot='{}' where id=?",order))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(()->jdbc.update("update orders set platform_fee_rule_snapshot=jsonb_set(platform_fee_rule_snapshot,'{currency}','\"EUR\"') where id=?",order))
                    .isInstanceOf(DataIntegrityViolationException.class);
            try(var resources=new ClassLoaderResourceAccessor()) { update(postgres,resources); }
            assertThat(jdbc.queryForObject("select count(*) from databasechangelog where id='008-ordering-constraint-upgrade'",Integer.class)).isEqualTo(1);
        }
    }

    private void prepareHistoricalResources(String version) throws Exception {
        var source=Path.of("src/main/resources/db/changelog");
        var destination=Files.createDirectories(historicalResources.resolve("db/changelog"));
        try(var files=Files.list(source)) {
            for(var file:files.toList()){Files.copy(file,destination.resolve(file.getFileName()));}
        }
        String master=Files.readString(source.resolve("db.changelog-master.yaml")).replace("\r\n","\n");
        master=master.substring(0,master.indexOf("  - include:\n      file: 008-"));
        if(!version.equals("current007")){master=master.substring(0,master.indexOf("  - include:\n      file: 007-"));}
        Files.writeString(destination.resolve("db.changelog-master.yaml"),master);
        String sql=Files.readString(source.resolve("006-ordering.sql")).replace("\r\n","\n")
                .replace("--validCheckSum: "+LEGACY_CHECKSUM+"\n","");
        if(version.equals("legacy006")) {
            sql=sql.replace("'CONFIRMED','EXPIRED','CANCELLED'","'CONFIRMED','CANCELLED'")
                    .replace("'PICKED_UP','CANCELLED'","'PICKED_UP'");
            int start=sql.indexOf("    CONSTRAINT ck_orders_snapshots");
            int end=sql.indexOf("    CONSTRAINT ck_orders_shipping");
            sql=sql.substring(0,start)+"""
                    CONSTRAINT ck_orders_snapshots CHECK (jsonb_typeof(customer_contact_snapshot) = 'object'
                        AND jsonb_typeof(platform_fee_rule_snapshot) = 'object' AND jsonb_typeof(shipping_terms_snapshot) = 'object'),
                """+sql.substring(end);
        }
        Files.writeString(destination.resolve("006-ordering.sql"),sql);
    }

    private void update(PostgreSQLContainer postgres,ResourceAccessor resources) throws Exception {
        var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        var database=DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        try(var liquibase=new Liquibase(MASTER,resources,database)) { liquibase.update(new Contexts(),new LabelExpression()); }
    }

    private UUID seedOrder(JdbcTemplate jdbc) {
        UUID organization=UUID.randomUUID(),campaign=UUID.randomUUID(),customer=UUID.randomUUID(),order=UUID.randomUUID();
        jdbc.update("insert into organizations(id,name,status,created_at,updated_at) values (?,'Migration fixture','ACTIVE',now(),now())",organization);
        jdbc.update("insert into grouping_campaigns(id,organization_id,public_identifier,slug,title,status,currency,created_at,updated_at) values (?,?,?,'migration','Migration','DRAFT','XOF',now(),now())",campaign,organization,UUID.randomUUID());
        jdbc.update("insert into customers(id,organization_id,first_name,last_name,phone_e164,created_at,updated_at) values (?,?,'Migration','Fixture','+221771234567',now(),now())",customer,organization);
        jdbc.update("""
            insert into orders(id,organization_id,campaign_id,customer_id,order_number,status,fulfillment_status,currency,
                customer_contact_snapshot,goods_subtotal,platform_fee_amount,platform_fee_rule_snapshot,shipping_terms_snapshot,
                initial_shipping_charge_amount,initial_amount_due,checkout_idempotency_key,checkout_request_fingerprint,
                tracking_token_hash,payment_deadline,tracking_token_expires_at,placed_at)
            values (?,?,?,?,'GRP-000000000001','PENDING_PAYMENT','NOT_READY','XOF',
                '{"firstName":"Migration","lastName":"Fixture","phoneE164":"+221771234567"}',1000,300,
                '{"type":"FIXED","currency":"XOF","amount":300,"version":1}',
                '{"shippingCollectionMode":"AFTER_FINAL_ASSESSMENT","shippingPricingMethod":"FIXED","shippingRate":0,"shippingCurrency":"XOF","roundingRule":"HALF_UP"}',
                0,1300,'migration-test-key',repeat('a',64),repeat('b',64),now()+interval '30 minutes',now()+interval '180 days',now())
            """,order,organization,campaign,customer);
        return order;
    }
}
