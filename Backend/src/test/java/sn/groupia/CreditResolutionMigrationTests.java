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

class CreditResolutionMigrationTests {
    @TempDir Path historical;

    @Test
    void upgradeFrom018PreservesExistingFactsAndAddsProtectedRefunds() throws Exception {
        var source = Path.of("src/main/resources/db/changelog");
        var dest = Files.createDirectories(historical.resolve("db/changelog"));
        try (var paths = Files.list(source)) {
            for (var p : paths.toList()) Files.copy(p, dest.resolve(p.getFileName()));
        }
        String master =
                Files.readString(source.resolve("db.changelog-master.yaml")).replace("\r\n", "\n");
        Files.writeString(
                dest.resolve("db.changelog-master.yaml"),
                master.substring(0, master.indexOf("  - include:\n      file: 019-")));
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
            UUID order = migrations.seedOrder(jdbc);
            jdbc.update(
                    "insert into order_fulfillment_events(id,organization_id,campaign_id,order_id,type,actor_id,recorded_at) select gen_random_uuid(),organization_id,campaign_id,id,'READY_FOR_PICKUP',gen_random_uuid(),now() from orders where id=?",
                    order);
            var before = jdbc.queryForList("select * from orders order by id");
            var events =
                    jdbc.queryForList(
                            "select * from order_fulfillment_events order by recording_sequence");
            var checksums =
                    jdbc.queryForList(
                            "select id,md5sum from databasechangelog order by orderexecuted");
            try (var resources = new ClassLoaderResourceAccessor()) {
                migrations.migrate(db, resources);
            }
            assertThat(jdbc.queryForList("select * from orders order by id")).isEqualTo(before);
            assertThat(
                            jdbc.queryForList(
                                    "select * from order_fulfillment_events order by recording_sequence"))
                    .isEqualTo(events);
            assertThat(
                            jdbc.queryForList(
                                    "select id,md5sum from databasechangelog where id not like '019-%' and id not like '020-%' and id not like '021-%' and id not like '022-%' and id not like '023-%' and id not like '024-%' and id not like '025-%' and id not like '026-%' order by orderexecuted"))
                    .isEqualTo(checksums);
            assertThat(jdbc.queryForObject("select count(*) from payment_refunds", Integer.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from refund_webhook_receipts", Integer.class))
                    .isZero();
            for (String field :
                    List.of(
                            "amount",
                            "currency",
                            "recipient_phone_snapshot",
                            "business_reference",
                            "provider_idempotency_key")) {
                String sql = insert(order);
                if (field.equals("amount")) sql = sql.replace("500,'XOF'", "0,'XOF'");
                if (field.equals("currency")) sql = sql.replace("500,'XOF'", "500,'EUR'");
                if (field.equals("recipient_phone_snapshot"))
                    sql = sql.replace("'+221771234567'", "null");
                if (field.equals("business_reference"))
                    sql = sql.replace("'credit-fixture'", "null");
                if (field.equals("provider_idempotency_key"))
                    sql = sql.replace("'10000000-0000-0000-0000-000000000001'", "null");
                final String invalid = sql;
                assertThatThrownBy(() -> jdbc.execute(invalid))
                        .isInstanceOf(
                                org.springframework.dao.DataIntegrityViolationException.class);
            }
            jdbc.execute(insert(order));
            assertThatThrownBy(() -> jdbc.execute(insert(order)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            jdbc.update(
                    "update payment_refunds set status='SUCCEEDED',completed_at=now(),reconciliation_required=false");
            assertThatThrownBy(() -> jdbc.update("update payment_refunds set amount=100"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("delete from payment_refunds"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            try (var resources = new ClassLoaderResourceAccessor()) {
                migrations.migrate(db, resources);
            }
            assertThat(jdbc.queryForObject("select count(*) from payment_refunds", Integer.class))
                    .isEqualTo(1);
        }
    }

    private String insert(UUID order) {
        return """
        insert into payment_refunds(id,organization_id,order_id,calculation_id,amount,currency,purpose,method,external_method,status,
            business_reference,merchant_reference,provider,provider_account_reference,provider_idempotency_key,recipient_phone_snapshot,
            recipient_name_snapshot,initiated_by,initiated_at,reconciliation_required,manual_reference,note)
        values(gen_random_uuid(),gen_random_uuid(),'%s',gen_random_uuid(),500,'XOF','SHIPPING_CREDIT','MANUAL_EXTERNAL','CASH','CREATED',
            'credit-fixture','GPR-fixture','MANUAL_EXTERNAL','internal','10000000-0000-0000-0000-000000000001','+221771234567',
            'Fixture',gen_random_uuid(),now(),false,null,'Restitution espèces')
        """
                .formatted(order);
    }
}
