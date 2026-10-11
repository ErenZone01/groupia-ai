package sn.groupia;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import liquibase.resource.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class PaymentReviewChecksumUpgradeTests {
    private static final String HISTORICAL = "9:982310b2ff67921dfa159b9ee836d093";
    private static final String CURRENT = "9:d8e3832bf57aecfbc7bad0ce01454bd9";
    @TempDir Path historical;

    @ParameterizedTest
    @ValueSource(strings = {"historical", "current", "unknown"})
    void acceptsOnlyKnown024ChecksumsWithoutRewritingFinancialHistory(String version)
            throws Exception {
        prepare(version);
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
            String checksum =
                    jdbc.queryForObject(
                            "select md5sum from databasechangelog where id='024-payment-reviews'",
                            String.class);
            if (version.equals("historical")) assertThat(checksum).isEqualTo(HISTORICAL);
            else if (version.equals("current")) assertThat(checksum).isEqualTo(CURRENT);
            else assertThat(checksum).isNotIn(HISTORICAL, CURRENT);

            var fixture = MerchantSettlementFixtures.seed(jdbc);
            UUID request = MerchantSettlementFixtures.settled(jdbc, fixture, "INITIAL_ORDER");
            for (String transaction : List.of("EXTRA-OPEN", "EXTRA-RESOLVED")) {
                jdbc.update(
                        """
                    insert into payment_reviews(id,organization_id,payment_request_id,principal_payment_id,anomalous_payment_id,
                        reason,status,anomalous_provider,anomalous_provider_account_reference,anomalous_provider_transaction_id,
                        observed_amount,observed_currency,detected_at)
                    select gen_random_uuid(),p.organization_id,p.payment_request_id,p.id,p.id,
                        'DUPLICATE_PROVIDER_SUCCESS','OPEN',p.provider,p.provider_account_reference,?,p.expected_amount,p.currency,now()
                    from payments p join payment_requests r on r.settled_payment_id=p.id where r.id=?
                    """,
                        transaction,
                        request);
            }
            jdbc.update(
                    """
                update payment_reviews set status='RESOLVED_REFUNDED',resolved_at=now(),resolved_by=?,
                    external_refund_reference='EXTERNAL-FIXTURE',resolution_note='Already refunded',version=version+1
                where anomalous_provider_transaction_id='EXTRA-RESOLVED'
                """,
                    fixture.actor());
            var before = new LinkedHashMap<String, List<Map<String, Object>>>();
            for (String table :
                    List.of(
                            "orders",
                            "payment_requests",
                            "payments",
                            "payment_reviews",
                            "payment_webhook_events"))
                before.put(table, jdbc.queryForList("select * from " + table + " order by id"));
            var checksums =
                    jdbc.queryForList(
                            "select id,md5sum from databasechangelog order by orderexecuted");
            String function =
                    jdbc.queryForObject(
                            "select pg_get_functiondef('protect_payment_review_history()'::regprocedure)",
                            String.class);
            try (var resources = new ClassLoaderResourceAccessor()) {
                if (version.equals("unknown")) {
                    assertThatThrownBy(() -> migrations.migrate(db, resources))
                            .hasStackTraceContaining("ValidationFailedException");
                } else {
                    migrations.migrate(db, resources);
                    migrations.migrate(db, resources);
                }
            }
            for (var entry : before.entrySet())
                assertThat(jdbc.queryForList("select * from " + entry.getKey() + " order by id"))
                        .isEqualTo(entry.getValue());
            assertThat(
                            jdbc.queryForList(
                                    "select id,md5sum from databasechangelog order by orderexecuted"))
                    .isEqualTo(checksums);
            assertThat(
                            jdbc.queryForObject(
                                    "select pg_get_functiondef('protect_payment_review_history()'::regprocedure)",
                                    String.class))
                    .isEqualTo(function);
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "update payment_requests set settled_payment_id=null where id=?",
                                            request))
                    .hasStackTraceContaining("Allocated payment principal is immutable");
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "update payment_reviews set observed_amount=observed_amount+1"))
                    .hasStackTraceContaining(
                            "Payment review evidence and resolution are immutable");
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "update payment_reviews set status='OPEN' where status='RESOLVED_REFUNDED'"))
                    .hasStackTraceContaining(
                            "Payment review evidence and resolution are immutable");
        }
    }

    private void prepare(String version) throws Exception {
        var source = Path.of("src/main/resources/db/changelog");
        var dest = Files.createDirectories(historical.resolve("db/changelog"));
        try (var paths = Files.list(source)) {
            for (var path : paths.toList()) Files.copy(path, dest.resolve(path.getFileName()));
        }
        String sql =
                Files.readString(source.resolve("024-payment-reviews.sql"))
                        .replace("\r\n", "\n")
                        .replace("--validCheckSum: " + HISTORICAL + "\n", "");
        if (version.equals("historical")) {
            sql =
                    sql.replace(
                            "DECLARE\n    obligation payment_requests%ROWTYPE;\n    principal payments%ROWTYPE;\n    attempt payments%ROWTYPE;",
                            "DECLARE obligation payment_requests%ROWTYPE;\nDECLARE principal payments%ROWTYPE;\nDECLARE attempt payments%ROWTYPE;");
        }
        if (version.equals("unknown"))
            sql = sql.replace("resolution_note VARCHAR(1000)", "resolution_note VARCHAR(999)");
        Files.writeString(dest.resolve("024-payment-reviews.sql"), sql);
    }
}
