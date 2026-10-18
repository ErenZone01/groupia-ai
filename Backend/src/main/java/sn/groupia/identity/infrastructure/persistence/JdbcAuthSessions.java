package sn.groupia.identity.infrastructure.persistence;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import sn.groupia.identity.domain.model.AuthSession;
import sn.groupia.identity.domain.repository.AuthSessions;

@Repository
public class JdbcAuthSessions implements AuthSessions {
    private final JdbcTemplate jdbc;

    public JdbcAuthSessions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Token> token(byte[] hash) {
        if (hash == null) return Optional.empty();
        return jdbc
                .query(
                        "select session_id,generation,consumed_at from auth_refresh_tokens where token_hash=?",
                        (r, n) ->
                                new Token(r.getObject(1, UUID.class), r.getLong(2), instant(r, 3)),
                        hash)
                .stream()
                .findFirst();
    }

    public Optional<UUID> user(UUID id) {
        return jdbc
                .query(
                        "select user_account_id from auth_sessions where id=?",
                        (r, n) -> r.getObject(1, UUID.class),
                        id)
                .stream()
                .findFirst();
    }

    public boolean lockActiveAccount(UUID user) {
        return jdbc
                .query(
                        "select status from user_accounts where id=? for share",
                        (r, n) -> r.getString(1),
                        user)
                .stream()
                .anyMatch("ACTIVE"::equals);
    }

    public Optional<AuthSession> lock(UUID id) {
        return jdbc
                .query(
                        "select id,user_account_id,authenticated_at,absolute_expires_at,idle_expires_at,revoked_at,refresh_window_started_at,refresh_attempts from auth_sessions where id=? for update",
                        (r, n) ->
                                new AuthSession(
                                        r.getObject(1, UUID.class),
                                        r.getObject(2, UUID.class),
                                        instant(r, 3),
                                        instant(r, 4),
                                        instant(r, 5),
                                        instant(r, 6),
                                        instant(r, 7),
                                        r.getInt(8)),
                        id)
                .stream()
                .findFirst();
    }

    public void create(AuthSession s, byte[] key, Instant now) {
        jdbc.update(
                "insert into auth_sessions(id,user_account_id,created_at,authenticated_at,last_used_at,absolute_expires_at,idle_expires_at,refresh_limit_key,refresh_window_started_at,refresh_attempts) values (?,?,?,?,?,?,?,?,?,0)",
                s.id(),
                s.userAccountId(),
                ts(now),
                ts(s.authenticatedAt()),
                ts(now),
                ts(s.absoluteExpiresAt()),
                ts(s.idleExpiresAt()),
                key,
                ts(now));
    }

    public void insertToken(UUID id, long generation, byte[] hash, Instant now) {
        jdbc.update(
                "insert into auth_refresh_tokens(session_id,generation,token_hash,issued_at) values (?,?,?,?)",
                id,
                generation,
                hash,
                ts(now));
    }

    public void consume(Token t, Instant now) {
        if (jdbc.update(
                        "update auth_refresh_tokens set consumed_at=? where session_id=? and generation=? and consumed_at is null",
                        ts(now),
                        t.sessionId(),
                        t.generation())
                != 1) throw new IllegalStateException("Refresh rotation conflict");
    }

    public void touch(UUID id, Instant now, Instant idle) {
        jdbc.update(
                "update auth_sessions set last_used_at=?,idle_expires_at=? where id=?",
                ts(now),
                ts(idle),
                id);
    }

    public void revoke(UUID id, Instant now, String reason) {
        jdbc.update(
                "update auth_sessions set revoked_at=?,revocation_reason=? where id=? and revoked_at is null",
                ts(now),
                reason,
                id);
    }

    public boolean admit(AuthSession s, byte[] key, Instant now, int maximum, Duration window) {
        boolean reset = !now.isBefore(s.refreshWindowStartedAt().plus(window));
        if (!reset && s.refreshAttempts() >= maximum) return false;
        int changed =
                jdbc.update(
                        "update auth_sessions set refresh_attempts=?,refresh_window_started_at=? where id=? and refresh_limit_key=?",
                        reset ? 1 : s.refreshAttempts() + 1,
                        ts(reset ? now : s.refreshWindowStartedAt()),
                        s.id(),
                        key);
        if (changed != 1) throw new IllegalStateException("Refresh quota identity mismatch");
        return true;
    }

    public int purge(Instant now, int limit) {
        return jdbc.update(
                "delete from auth_sessions where id in (select id from auth_sessions where absolute_expires_at<=? order by absolute_expires_at limit ? for update skip locked)",
                ts(now),
                limit);
    }

    private static Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }

    private static Instant instant(ResultSet r, int i) throws SQLException {
        var t = r.getTimestamp(i);
        return t == null ? null : t.toInstant();
    }
}
