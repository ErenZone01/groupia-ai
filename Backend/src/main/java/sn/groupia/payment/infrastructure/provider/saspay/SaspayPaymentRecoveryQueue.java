package sn.groupia.payment.infrastructure.provider.saspay;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable scheduling, separate from financial evidence and incident fingerprints. */
@Service
public class SaspayPaymentRecoveryQueue {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SaspayPaymentRecoveryQueue(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Work(UUID paymentId, UUID token, int attempt) {}

    @Transactional
    public List<Work> claim(String account) {
        var now = Timestamp.from(clock.instant());
        // Seed incrementally, including old legacy attempts with or without a quote/ID.
        // No backfill mutates financial records. Recently claimed POSTs get a quiet period.
        jdbc.update(
                """
                INSERT INTO saspay_payment_recovery(payment_id, next_check_at)
                SELECT p.id, ? FROM payments p
                WHERE p.provider='SASPAY' AND p.provider_account_reference=?
                  AND p.status IN ('CREATED','PENDING') AND p.initiated_at < ?
                  AND NOT EXISTS (SELECT 1 FROM saspay_payment_recovery r WHERE r.payment_id=p.id)
                ORDER BY p.initiated_at, p.id LIMIT 100
                ON CONFLICT (payment_id) DO NOTHING
                """,
                now,
                account,
                Timestamp.from(clock.instant().minusSeconds(60)));
        UUID token = UUID.randomUUID();
        return jdbc.query(
                """
                WITH due AS (
                    SELECT r.payment_id FROM saspay_payment_recovery r
                    JOIN payments p ON p.id=r.payment_id
                    WHERE p.provider='SASPAY' AND p.provider_account_reference=?
                      AND p.status IN ('CREATED','PENDING')
                      AND r.next_check_at <= ? AND (r.lease_until IS NULL OR r.lease_until <= ?)
                    ORDER BY r.next_check_at, r.payment_id
                    LIMIT 25 FOR UPDATE OF r SKIP LOCKED
                )
                UPDATE saspay_payment_recovery r
                SET claim_token=?, lease_until=?, last_attempt_at=?,
                    attempt_count=LEAST(r.attempt_count, 1000000)+1
                FROM due WHERE r.payment_id=due.payment_id
                RETURNING r.payment_id, r.attempt_count
                """,
                (rs, n) -> new Work(rs.getObject(1, UUID.class), token, rs.getInt(2)),
                account,
                now,
                now,
                token,
                Timestamp.from(clock.instant().plusSeconds(1800)),
                now);
    }

    @Transactional
    public void complete(Work work) {
        // Persist even a failed verification's next due time; oldest failures cannot starve others.
        long delay = Math.min(3600, 60L << Math.min(6, Math.max(0, work.attempt() - 1)));
        jdbc.update(
                """
                UPDATE saspay_payment_recovery r
                SET next_check_at=CASE WHEN p.reconciliation_required THEN CAST(? AS timestamptz) ELSE CAST(? AS timestamptz) END,
                    lease_until=NULL, claim_token=NULL, last_completed_at=?,
                    last_result=COALESCE(p.reconciliation_reason,p.status)
                FROM payments p WHERE p.id=r.payment_id AND r.payment_id=? AND r.claim_token=?
                """,
                Timestamp.from(clock.instant().plusSeconds(delay)),
                Timestamp.from(clock.instant().plusSeconds(60)),
                Timestamp.from(clock.instant()),
                work.paymentId(),
                work.token());
    }
}
