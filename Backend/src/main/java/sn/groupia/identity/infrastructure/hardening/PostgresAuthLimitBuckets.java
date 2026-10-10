package sn.groupia.identity.infrastructure.hardening;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import sn.groupia.identity.domain.repository.AuthLimitBuckets;

/**
 * Shared across instances. The short admission lock never covers password hashing or user creation.
 */
@Repository
public class PostgresAuthLimitBuckets implements AuthLimitBuckets {
    private final JdbcTemplate jdbc;

    public PostgresAuthLimitBuckets(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consume(String scope, String hash, int maximum, Duration window, int capacity) {
        jdbc.query("select pg_advisory_xact_lock(742019023)", (r, n) -> 0);
        Instant now = jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
        var row =
                jdbc.query(
                        "select attempts,expires_at from auth_rate_limits where scope=? and key_hash=?",
                        (r, n) -> new Bucket(r.getInt(1), r.getTimestamp(2).toInstant()),
                        scope,
                        hash);
        if (!row.isEmpty() && row.getFirst().until().isAfter(now)) {
            if (row.getFirst().attempts() >= maximum) return false;
            jdbc.update(
                    "update auth_rate_limits set attempts=attempts+1 where scope=? and key_hash=?",
                    scope,
                    hash);
            return true;
        }
        if (row.isEmpty()) {
            cleanup();
            if (jdbc.queryForObject("select count(*) from auth_rate_limits", Long.class)
                    >= capacity) return false;
        }
        jdbc.update(
                """
   insert into auth_rate_limits(scope,key_hash,attempts,expires_at) values (?,?,1,?)
   on conflict(scope,key_hash) do update set attempts=1,expires_at=excluded.expires_at
   """,
                scope,
                hash,
                Timestamp.from(now.plus(window)));
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cleanup() {
        return jdbc.update(
                """
   delete from auth_rate_limits where (scope,key_hash) in
   (select scope,key_hash from auth_rate_limits where expires_at<=CURRENT_TIMESTAMP order by expires_at limit 1000)
   """);
    }

    private record Bucket(int attempts, Instant until) {}
}
