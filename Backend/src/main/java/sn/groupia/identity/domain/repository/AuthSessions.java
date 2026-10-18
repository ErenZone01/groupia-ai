package sn.groupia.identity.domain.repository;

import java.time.Instant;
import java.util.*;
import sn.groupia.identity.domain.model.AuthSession;

public interface AuthSessions {
    record Token(UUID sessionId, long generation, Instant consumedAt) {}

    Optional<Token> token(byte[] hash);

    Optional<UUID> user(UUID session);

    boolean lockActiveAccount(UUID user);

    Optional<AuthSession> lock(UUID id);

    void create(AuthSession session, byte[] quotaKey, Instant now);

    void insertToken(UUID session, long generation, byte[] hash, Instant now);

    void consume(Token token, Instant now);

    void touch(UUID id, Instant now, Instant idle);

    void revoke(UUID id, Instant now, String reason);

    boolean admit(
            AuthSession session, byte[] key, Instant now, int maximum, java.time.Duration window);

    int purge(Instant now, int limit);
}
