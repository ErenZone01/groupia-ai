package sn.groupia.identity.application.service;

import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.identity.domain.model.AuthSession;
import sn.groupia.identity.domain.repository.AuthSessions;
import sn.groupia.identity.infrastructure.security.*;
import sn.groupia.shared.api.ApiException;

@Service
public class RefreshSessions {
    private final AuthSessions store;
    private final RefreshSecrets secrets;
    private final SessionSettings settings;
    private final AccessTokens tokens;
    private final Clock clock;

    public RefreshSessions(
            AuthSessions store,
            RefreshSecrets secrets,
            SessionSettings settings,
            AccessTokens tokens,
            Clock clock) {
        this.store = store;
        this.secrets = secrets;
        this.settings = settings;
        this.tokens = tokens;
        this.clock = clock;
    }

    public record Issued(
            AccessTokens.AccessToken access, String refreshToken, Instant cookieExpiresAt) {
        @Override
        public String toString() {
            return "IssuedSession[REDACTED]";
        }
    }

    public record Result(Issued issued, String error, int status, long retryAfterSeconds) {
        static Result reject(String code, int status) {
            return new Result(null, code, status, 0);
        }

        @Override
        public String toString() {
            return "SessionResult[REDACTED]";
        }
    }

    @Transactional
    public Issued create(UUID user, String previous) {
        if (!store.lockActiveAccount(user))
            throw new ApiException(401, "INVALID_CREDENTIALS", "Les identifiants sont invalides.");
        var now = clock.instant();
        store.token(secrets.tokenHash(previous))
                .ifPresent(
                        t -> {
                            store.lock(t.sessionId())
                                    .ifPresent(s -> store.revoke(s.id(), now, "REPLACED"));
                        });
        UUID id = UUID.randomUUID();
        var absolute = now.plus(settings.absoluteTtl());
        var idle = min(absolute, now.plus(settings.idleTtl()));
        var session = new AuthSession(id, user, now, absolute, idle, null, now, 0);
        store.create(session, secrets.quotaKey(id), now);
        String raw = secrets.generate();
        store.insertToken(id, 1, secrets.tokenHash(raw), now);
        return new Issued(tokens.issue(user, id, now, absolute), raw, idle);
    }

    @Transactional
    public Result refresh(String raw) {
        byte[] hash = secrets.tokenHash(raw);
        var candidate = store.token(hash);
        if (candidate.isEmpty()) return Result.reject("AUTH_REFRESH_INVALID", 401);
        // Lock order: account then session, also used by login.
        var user = store.user(candidate.get().sessionId());
        if (user.isEmpty()) return Result.reject("AUTH_REFRESH_INVALID", 401);
        boolean active = store.lockActiveAccount(user.get());
        // This row lock serializes rotation, replay revocation and logout across all instances.
        var found = store.lock(candidate.get().sessionId());
        if (found.isEmpty()) return Result.reject("AUTH_REFRESH_INVALID", 401);
        var s = found.get();
        var now = clock.instant();
        if (s.revokedAt() != null) return Result.reject("AUTH_REFRESH_INVALID", 401);
        if (!active) {
            store.revoke(s.id(), now, "ACCOUNT_DISABLED");
            return Result.reject("AUTH_REFRESH_INVALID", 401);
        }
        if (!now.isBefore(s.absoluteExpiresAt()) || !now.isBefore(s.idleExpiresAt())) {
            store.revoke(s.id(), now, "EXPIRED");
            return Result.reject("AUTH_REFRESH_INVALID", 401);
        }
        var token = store.token(hash).orElseThrow();
        // Replay revocation must commit even when the quota is exhausted.
        if (token.consumedAt() != null
                && now.isAfter(token.consumedAt().plus(settings.rotationGrace()))) {
            store.revoke(s.id(), now, "REUSED");
            return Result.reject("AUTH_REFRESH_REUSED", 401);
        }
        if (!store.admit(
                s,
                secrets.quotaKey(s.id()),
                now,
                settings.refreshLimit(),
                settings.refreshWindow()))
            return new Result(
                    null,
                    "AUTH_REFRESH_RATE_LIMITED",
                    429,
                    Math.max(
                            1,
                            Duration.between(
                                            now,
                                            s.refreshWindowStartedAt()
                                                    .plus(settings.refreshWindow()))
                                    .toSeconds()));
        if (token.consumedAt() != null) return Result.reject("AUTH_REFRESH_CONCURRENT", 409);
        store.consume(token, now);
        String next = secrets.generate();
        store.insertToken(
                s.id(), Math.addExact(token.generation(), 1), secrets.tokenHash(next), now);
        var idle = min(s.absoluteExpiresAt(), now.plus(settings.idleTtl()));
        store.touch(s.id(), now, idle);
        return new Result(
                new Issued(
                        tokens.issue(
                                s.userAccountId(),
                                s.id(),
                                s.authenticatedAt(),
                                s.absoluteExpiresAt()),
                        next,
                        idle),
                null,
                200,
                0);
    }

    @Transactional
    public void logout(String raw) {
        store.token(secrets.tokenHash(raw))
                .ifPresent(
                        t -> {
                            store.lock(t.sessionId())
                                    .ifPresent(
                                            s -> store.revoke(s.id(), clock.instant(), "LOGOUT"));
                        });
    }

    @Transactional
    @Scheduled(
            fixedDelayString = "${groupia.security.session.cleanup-delay:3600000}",
            initialDelayString = "${groupia.security.session.cleanup-delay:3600000}")
    public void purge() {
        store.purge(clock.instant(), 100);
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
