package sn.groupia.media.domain.model;

import java.time.Instant;
import java.util.UUID;

public record ProductMedia(
        UUID id,
        UUID organizationId,
        UUID productId,
        State state,
        Integer position,
        MediaRendition display,
        MediaRendition thumbnail,
        UUID idempotencyKey,
        String fingerprint,
        Instant createdAt,
        Instant uploadExpiresAt,
        int cleanupAttempts,
        String lastErrorCode) {
    public enum State {
        UPLOADING,
        READY,
        DELETE_PENDING,
        DELETED
    }
}
