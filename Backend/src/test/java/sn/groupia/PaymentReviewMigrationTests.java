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

class PaymentReviewMigrationTests {
    @TempDir Path historical;

    @Test
    void upgradeFrom023PreservesAllFinancialFactsAndDoesNotBackfillReviews() throws Exception {
        var source = Path.of("src/main/resources/db/changelog");
        var dest = Files.createDirectories(historical.resolve("db/changelog"));
        try (var paths = Files.list(source)) {
            for (var path : paths.toList()) Files.copy(path, dest.resolve(path.getFileName()));
        }
        String master =
                Files.readString(source.resolve("db.changelog-master.yaml")).replace("\r\n", "\n");
        Files.writeString(
                dest.resolve("db.changelog-master.yaml"),
                master.substring(0, master.indexOf("  - include:\n      file: 024-")));
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
            var fixture = MerchantSettlementFixtures.seed(jdbc);
            UUID request =
                    MerchantSettlementFixtures.settledBefore026(jdbc, fixture, "INITIAL_ORDER");
            jdbc.update(
                    """
                insert into payment_webhook_events(id,provider,provider_account_reference,deduplication_key,
                    organization_id,payment_id,authenticity_verification_result,payload_fingerprint,
                    processing_status,received_at,attempt_count,last_error_code)
                select gen_random_uuid(),provider,provider_account_reference,repeat('a',64),organization_id,id,
                    'VALID',repeat('b',64),'REVIEW_REQUIRED',now(),0,'DUPLICATE_SUCCESS' from payments
                """);
            var before = new LinkedHashMap<String, List<Map<String, Object>>>();
            for (String table :
                    List.of("orders", "payment_requests", "payments", "payment_webhook_events"))
                before.put(table, jdbc.queryForList("select * from " + table + " order by id"));
            var checksums = MigrationHistoryAssertions.snapshot(jdbc);
            try (var resources = new ClassLoaderResourceAccessor()) {
                migrations.migrate(db, resources);
            }
            for (var entry : before.entrySet())
                assertThat(
                                jdbc
                                        .queryForList(
                                                "select * from " + entry.getKey() + " order by id")
                                        .stream()
                                        .map(
                                                row -> {
                                                    if (entry.getKey().equals("payment_requests")) {
                                                        row.remove("target_type");
                                                        row.remove("target_id");
                                                        assertThat(row.remove("direct_order_id")).isNull();
                                                    }
                                                    return row;
                                                })
                                        .toList())
                        .isEqualTo(entry.getValue());
            MigrationHistoryAssertions.assertPreserved(jdbc, checksums);
            assertThat(jdbc.queryForObject("select count(*) from payment_reviews", Long.class))
                    .isZero();
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "update payment_requests set settled_payment_id=? where id=?",
                                            UUID.randomUUID(),
                                            request))
                    .hasStackTraceContaining("Allocated payment principal is immutable");
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "update payment_requests set settled_payment_id=null,acceptance_status='OPEN',closed_at=null where id=?",
                                            request))
                    .hasStackTraceContaining("Allocated payment principal is immutable");
            try (var resources = new ClassLoaderResourceAccessor()) {
                migrations.migrate(db, resources);
            }
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from databasechangelog where id='024-payment-reviews'",
                                    Long.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from payment_reviews", Long.class))
                    .isZero();
        }
    }
}
