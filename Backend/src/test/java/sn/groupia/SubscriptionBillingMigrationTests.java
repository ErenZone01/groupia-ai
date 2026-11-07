package sn.groupia;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import liquibase.*;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class SubscriptionBillingMigrationTests {
    @TempDir Path root;

    @Test
    void upgrades025WithoutRewritingFinancialHistoryAndRejectsInvalidTargets() throws Exception {
        Path destination = Files.createDirectories(root.resolve("db/changelog"));
        try (var files = Files.list(Path.of("src/main/resources/db/changelog"))) {
            for (var file : files.toList())
                Files.copy(file, destination.resolve(file.getFileName()));
        }
        Path master = destination.resolve("db.changelog-master.yaml");
        String full = Files.readString(master).replace("\r\n", "\n");
        Files.writeString(
                master, full.substring(0, full.indexOf("  - include:\n      file: 026-")));
        try (var db = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))) {
            db.start();
            migrate(db);
            var j =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    db.getJdbcUrl(), db.getUsername(), db.getPassword()));
            var f = MerchantSettlementFixtures.seed(j);
            UUID request = UUID.randomUUID(),
                    payment = UUID.randomUUID(),
                    subscription = UUID.randomUUID();
            j.update(
                    """
                insert into payment_requests(id,organization_id,order_id,purpose,business_reference,expected_amount,goods_amount,fee_amount,shipping_amount,currency,acceptance_status,expires_at,created_at)
                values (?,?,?,'INITIAL_ORDER',?,25300,20000,300,5000,'XOF','OPEN',now()+interval '1 hour',now())
                """,
                    request,
                    f.org(),
                    f.order(),
                    "INITIAL_ORDER:" + f.order());
            j.update(
                    """
                insert into payments(id,organization_id,payment_request_id,provider,merchant_reference,provider_transaction_id,provider_idempotency_key,initiation_key,expected_amount,currency,confirmed_amount,confirmed_currency,status,reconciliation_required,initiated_at,confirmed_at)
                values (?,?,?,'LEGACY',?,?,?,?,25300,'XOF',25300,'XOF','SUCCEEDED',false,now(),now())
                """,
                    payment,
                    f.org(),
                    request,
                    "GP-" + payment,
                    "TX-" + payment,
                    payment.toString(),
                    payment.toString());
            j.update(
                    "update payment_requests set acceptance_status='CLOSED',settled_payment_id=?,closed_at=now() where id=?",
                    payment,
                    request);
            j.update(
                    """
                insert into organization_subscriptions(id,organization_id,status,trial_started_at,trial_ends_at,trial_provenance,created_at,updated_at)
                values (?,?,'ACTIVE',now()-interval '2 months',now()-interval '1 month','ORGANIZATION_CREATED',now(),now())
                """,
                    subscription,
                    f.org());
            j.update(
                    """
                insert into subscription_periods(id,organization_id,subscription_id,plan_code,amount,currency,billing_interval,period_start,period_end,provider,provider_transaction_id,provider_reference,status,paid_at,created_at)
                values (?,?,?,'STANDARD_MONTHLY',1234,'XOF','MONTHLY',now(),now()+interval '1 month','LEGACY','old-tx','old-ref','PAID',now(),now())
                """,
                    UUID.randomUUID(),
                    f.org(),
                    subscription);
            j.update(
                    """
                insert into payment_reviews(id,organization_id,payment_request_id,principal_payment_id,anomalous_payment_id,reason,status,anomalous_provider,anomalous_provider_transaction_id,observed_amount,observed_currency,detected_at)
                values (?,?,?,?,?,'DUPLICATE_PROVIDER_SUCCESS','OPEN','LEGACY','extra-legacy',25300,'XOF',now())
                """,
                    UUID.randomUUID(),
                    f.org(),
                    request,
                    payment,
                    payment);
            var reviewsBefore = j.queryForList("select * from payment_reviews");
            var requestBefore =
                    j.queryForObject(
                            "select to_jsonb(r)::text from payment_requests r where id=?",
                            String.class,
                            request);
            var periodsBefore =
                    j.queryForObject(
                            "select to_jsonb(p)::text from subscription_periods p", String.class);
            var paymentsBefore = j.queryForList("select * from payments");
            var sums = MigrationHistoryAssertions.snapshot(j);
            Files.writeString(master, full);
            migrate(db);
            assertThat(
                            j.queryForObject(
                                    "select (to_jsonb(r)-'target_type'-'target_id'-'direct_order_id')::text from payment_requests r where id=?",
                                    String.class,
                                    request))
                    .isEqualTo(requestBefore);
            assertThat(
                            j.queryForObject(
                                    "select direct_order_id from payment_requests where id=?",
                                    UUID.class,
                                    request))
                    .isNull();
            assertThat(
                            j.queryForObject(
                                    "select (to_jsonb(p)-'subscription_invoice_id'-'payment_request_id'-'payment_id'-'provider_account_reference')::text from subscription_periods p",
                                    String.class))
                    .isEqualTo(periodsBefore);
            var paymentsAfter = j.queryForList("select * from payments");
            for (var row : paymentsAfter) assertThat(row.remove("fee_quote_id")).isNull();
            assertThat(paymentsAfter).isEqualTo(paymentsBefore);
            assertThat(j.queryForList("select * from payment_reviews")).isEqualTo(reviewsBefore);
            MigrationHistoryAssertions.assertPreserved(j, sums);
            assertThat(
                            j.queryForObject(
                                    "select target_type from payment_requests where id=?",
                                    String.class,
                                    request))
                    .isEqualTo("ORDER");
            assertThat(
                            j.queryForObject(
                                    "select target_id from payment_requests where id=?",
                                    UUID.class,
                                    request))
                    .isEqualTo(f.order());
            assertThat(
                            j.queryForObject(
                                    "select count(*) from subscription_invoices", Integer.class))
                    .isZero();
            assertThat(
                            j.queryForObject(
                                    "select billing_timezone from organization_subscriptions where id=?",
                                    String.class,
                                    subscription))
                    .isEqualTo("Africa/Dakar");
            assertThatThrownBy(
                            () ->
                                    j.update(
                                            "update payment_requests set target_type='SUBSCRIPTION_INVOICE' where id=?",
                                            request))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(
                            () ->
                                    j.update(
                                            "update organization_subscriptions set billing_timezone='Europe/Paris' where id=?",
                                            subscription))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(
                            j.queryForObject(
                                    "select count(*) from pg_constraint c join pg_class t on c.conrelid=t.oid join pg_class ref on c.confrelid=ref.oid where t.relname='payment_requests' and ref.relname='subscription_invoices'",
                                    Integer.class))
                    .isZero();
            migrate(db);
            assertThat(j.queryForObject("select count(*) from subscription_periods", Integer.class))
                    .isEqualTo(1);
        }
    }

    private void migrate(PostgreSQLContainer db) throws Exception {
        var database =
                DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(
                                new JdbcConnection(
                                        DriverManager.getConnection(
                                                db.getJdbcUrl(),
                                                db.getUsername(),
                                                db.getPassword())));
        try (var resources = new DirectoryResourceAccessor(root);
                var liquibase =
                        new Liquibase(
                                "db/changelog/db.changelog-master.yaml", resources, database)) {
            liquibase.update(new Contexts(), new LabelExpression());
        }
    }
}
