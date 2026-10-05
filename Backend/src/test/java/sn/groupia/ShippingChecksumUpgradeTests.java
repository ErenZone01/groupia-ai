package sn.groupia;

import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

class ShippingChecksumUpgradeTests {
    private static final String LEGACY="9:055b3c7d7a68b80dc68d25a3ebcf7c71";
    private static final String CURRENT="9:b0f287a070c3d40ba7bb9be9bd29fef2";
    @TempDir Path historical;
    @ParameterizedTest
    @ValueSource(strings={"legacy","current","unknown","legacyInvalid"})
    void validatesKnownHistoryAndPreservesData(String version)throws Exception{
        prepare(version);
        try(var db=new PostgreSQLContainer(DockerImageName.parse("postgres:latest"))){
            db.start();
            try(var resources=new DirectoryResourceAccessor(historical)){migrate(db,resources);}
            var jdbc=new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()));
            String checksum=jdbc.queryForObject("select md5sum from databasechangelog where id='014-shipping-assessments'",String.class);
            if(version.startsWith("legacy"))assertThat(checksum).isEqualTo(LEGACY);
            else if(version.equals("current"))assertThat(checksum).isEqualTo(CURRENT);
            else assertThat(checksum).isNotIn(LEGACY,CURRENT);
            UUID order=seedOrder(jdbc),assessment=UUID.randomUUID(),measurement=UUID.randomUUID(),calculation=UUID.randomUUID(),actor=UUID.randomUUID();
            jdbc.update("""
                insert into shipping_assessments(id,organization_id,campaign_id,order_id,snapshot,status,version,created_at,updated_at)
                select ?,organization_id,campaign_id,id,jsonb_build_object('organizationId',organization_id,'campaignId',campaign_id,
                    'orderId',id,'terms',shipping_terms_snapshot,'totalItems',1),'PENDING',0,now(),now() from orders where id=?
                """,assessment,order);
            jdbc.update("insert into shipping_measurements(id,assessment_id,information,actor_id,recorded_at) values (?,?,?::jsonb,?,now())",
                measurement,assessment,"{\"actualWeightKg\":2.8,\"source\":\"FORWARDER\"}",actor);
            jdbc.update("update shipping_assessments set current_measurement_id=?,version=1,updated_at=now() where id=?",measurement,assessment);
            String valid="{\"origin\":\"PROVIDER_QUOTED\",\"currency\":\"XOF\",\"amount\":9800,\"roundingRule\":\"HALF_UP\",\"source\":\"FORWARDER\"}";
            insertCalculation(jdbc,calculation,assessment,actor,version.equals("legacyInvalid")?"{}":valid);
            jdbc.update("update shipping_assessments set current_final_calculation_id=?,status='FINALIZED',version=2,updated_at=now() where id=?",calculation,assessment);
            var tables=List.of("orders","shipping_assessments","shipping_measurements","shipping_calculations");
            var before=new HashMap<String,List<Map<String,Object>>>();
            tables.forEach(t->before.put(t,jdbc.queryForList("select * from "+t+" order by id")));
            if(version.equals("unknown")||version.equals("legacyInvalid")){
                try(var resources=new ClassLoaderResourceAccessor()){
                    assertThatThrownBy(()->migrate(db,resources))
                        .hasStackTraceContaining(version.equals("unknown")?"ValidationFailedException":"ck_shipping_calculation_evidence_shape");
                }
                assertThat(jdbc.queryForObject("select count(*) from databasechangelog where id='015-shipping-evidence-shape'",Integer.class)).isZero();
            }else{
                try(var resources=new ClassLoaderResourceAccessor()){migrate(db,resources);}
                assertThat(jdbc.queryForObject("select count(*) from databasechangelog where id='015-shipping-evidence-shape'",Integer.class)).isEqualTo(1);
                assertThatThrownBy(()->insertCalculation(jdbc,UUID.randomUUID(),assessment,actor,"{}"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .rootCause().isInstanceOfSatisfying(org.postgresql.util.PSQLException.class,
                        error->assertThat(error.getSQLState()).isEqualTo("23514"));
                // A new revision still cannot rewrite the original observation or calculation.
                assertThatThrownBy(()->jdbc.update("update shipping_calculations set note='rewrite' where id=?",calculation)).isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(()->jdbc.update("delete from shipping_measurements where id=?",measurement)).isInstanceOf(DataIntegrityViolationException.class);
                try(var resources=new ClassLoaderResourceAccessor()){migrate(db,resources);}
            }
            tables.forEach(t->{
                var after=jdbc.queryForList("select * from "+t+" order by id");
                if(t.equals("orders") && (version.equals("legacy") || version.equals("current"))){
                    after.forEach(row->{
                        assertThat(row).containsKey("ready_for_pickup_at");
                        assertThat(row.remove("ready_for_pickup_at")).isNull();
                    });
                }
                assertThat(after).as(t).isEqualTo(before.get(t));
            });
            assertThat(jdbc.queryForObject("select md5sum from databasechangelog where id='014-shipping-assessments'",String.class)).isEqualTo(checksum);
        }
    }
    private void insertCalculation(JdbcTemplate jdbc,UUID id,UUID assessment,UUID actor,String explanation){
        jdbc.update("insert into shipping_calculations(id,assessment_id,revision,kind,explanation,actor_id,recorded_at) values (?,?,1,'FINAL',?::jsonb,?,now())",
            id,assessment,explanation,actor);
    }
    private void prepare(String version)throws Exception{
        var source=Path.of("src/main/resources/db/changelog");
        var dest=Files.createDirectories(historical.resolve("db/changelog"));
        try(var paths=Files.list(source)){for(var p:paths.toList())Files.copy(p,dest.resolve(p.getFileName()));}
        var master=Files.readString(source.resolve("db.changelog-master.yaml")).replace("\r\n","\n");
        Files.writeString(dest.resolve("db.changelog-master.yaml"),master.substring(0,master.indexOf("  - include:\n      file: 015-")));
        var sql=Files.readString(source.resolve("014-shipping-assessments.sql")).replace("\r\n","\n")
            .replace("--validCheckSum: "+LEGACY+"\n","");
        if(version.startsWith("legacy")){
            int start=sql.indexOf("    CHECK(jsonb_typeof(explanation)");
            int end=sql.indexOf("    CHECK(explanation->>'origin'",start);
            sql=sql.substring(0,start)+sql.substring(end);
        }
        if(version.equals("unknown"))sql=sql.replace("NUMERIC;","NUMERIC(30,6);");
        Files.writeString(dest.resolve("014-shipping-assessments.sql"),sql);
    }
    private void migrate(PostgreSQLContainer db,ResourceAccessor resources)throws Exception{
        var database=DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(
            DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword())));
        try(var l=new Liquibase("db/changelog/db.changelog-master.yaml",resources,database)){l.update(new Contexts(),new LabelExpression());}
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
