package sn.groupia.identity.application.service;

import java.time.Instant;
import java.util.UUID;

public interface AccessTokens {
    AccessToken issue(
            UUID userAccountId, UUID sessionId, Instant authenticatedAt, Instant absoluteExpiresAt);

    record AccessToken(String accessToken, String tokenType, long expiresIn, UUID sessionId) {
        @Override
        public String toString() {
            return "AccessToken[REDACTED]";
        }
    }
}
