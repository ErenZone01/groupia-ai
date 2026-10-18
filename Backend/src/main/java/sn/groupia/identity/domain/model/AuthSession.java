package sn.groupia.identity.domain.model;

import java.time.Instant;
import java.util.UUID;

public record AuthSession(
        UUID id,
        UUID userAccountId,
        Instant authenticatedAt,
        Instant absoluteExpiresAt,
        Instant idleExpiresAt,
        Instant revokedAt,
        Instant refreshWindowStartedAt,
        int refreshAttempts) {}
