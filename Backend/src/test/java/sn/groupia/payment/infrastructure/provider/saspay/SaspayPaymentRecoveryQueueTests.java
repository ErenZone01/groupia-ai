package sn.groupia.payment.infrastructure.provider.saspay;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class SaspayPaymentRecoveryQueueTests {
    @Test
    void migrationAndReplicasRecoverEveryActiveShapeWithoutStarvationOrHistoricalMutation()
            throws Exception {
        try (var db = new PostgreSQLContainer("postgres:18.6")) {
            db.start();
            var jdbc =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    db.getJdbcUrl(), db.getUsername(), db.getPassword()));
            // Minimal pre-existing financial schema; full changelog also runs in application tests.
            jdbc.execute(
                    """
                    CREATE TABLE payments(id uuid primary key, provider text, provider_account_reference text,
                    status text, initiated_at timestamptz, reconciliation_reason text,
                    provider_charge_id text, provider_transaction_id text, fee_quote_id uuid,
                    reconciliation_required boolean)
                    """);
            Instant now = Instant.parse("2026-11-23T12:00:00Z");
            String account = UUID.randomUUID().toString();
            for (int n = 0; n < 32; n++) {
                jdbc.update(
                        "INSERT INTO payments VALUES(?, 'SASPAY', ?, ?, ?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID(),
                        n == 31 ? "other-account" : account,
                        n == 30 ? "SUCCEEDED" : n < 3 ? "CREATED" : "PENDING",
                        Timestamp.from(now.minusSeconds(3600)),
                        n < 3 ? "PROVIDER_STATE_UNCERTAIN" : null,
                        n < 4 ? null : UUID.randomUUID().toString(),
                        n == 3 ? UUID.randomUUID().toString() : null,
                        n < 4 ? UUID.randomUUID() : null,
                        n < 3);
            }
            var original = jdbc.queryForList("SELECT * FROM payments ORDER BY id");
            try (var sql =
                    getClass()
                            .getResourceAsStream("/db/changelog/064-saspay-payment-recovery.sql")) {
                jdbc.execute(new String(sql.readAllBytes(), StandardCharsets.UTF_8));
            }
            var a = new SaspayPaymentRecoveryQueue(jdbc, Clock.fixed(now, ZoneOffset.UTC));
            var b = new SaspayPaymentRecoveryQueue(jdbc, Clock.fixed(now, ZoneOffset.UTC));
            List<SaspayPaymentRecoveryQueue.Work> work = new ArrayList<>();
            try (var pool = Executors.newFixedThreadPool(2)) {
                var ready = new CountDownLatch(2);
                var start = new CountDownLatch(1);
                Callable<List<SaspayPaymentRecoveryQueue.Work>> first =
                        () -> {
                            ready.countDown();
                            start.await();
                            return a.claim(account);
                        };
                Callable<List<SaspayPaymentRecoveryQueue.Work>> second =
                        () -> {
                            ready.countDown();
                            start.await();
                            return b.claim(account);
                        };
                var one = pool.submit(first);
                var two = pool.submit(second);
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                work.addAll(one.get(15, TimeUnit.SECONDS));
                work.addAll(two.get(15, TimeUnit.SECONDS));
            }
            assertThat(work).hasSize(30);
            assertThat(work.stream().map(SaspayPaymentRecoveryQueue.Work::paymentId).distinct())
                    .hasSize(30);
            assertThat(a.claim(account)).isEmpty();
            // Simulate crash: leases expire; a restarted worker can resume all unfinished GETs.
            var restart =
                    new SaspayPaymentRecoveryQueue(
                            jdbc, Clock.fixed(now.plusSeconds(1801), ZoneOffset.UTC));
            var reclaimed = restart.claim(account);
            assertThat(reclaimed).hasSize(25).allMatch(w -> w.attempt() == 2);
            // Stale worker completion cannot clear the new worker's lease.
            work.forEach(a::complete);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM saspay_payment_recovery WHERE claim_token IS NOT NULL",
                                    Integer.class))
                    .isEqualTo(25);
            reclaimed.forEach(restart::complete);
            assertThat(restart.claim(account)).hasSize(5);
            assertThat(jdbc.queryForList("SELECT * FROM payments ORDER BY id")).isEqualTo(original);
        }
    }
}
