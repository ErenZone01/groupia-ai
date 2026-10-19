package sn.groupia.media.api.dto;

import java.time.Instant;
import java.util.UUID;

public record MediaView(
        UUID id,
        String state,
        Integer position,
        String displayUrl,
        String thumbnailUrl,
        String contentType,
        int width,
        int height,
        long sizeBytes,
        Instant createdAt,
        String errorCode) {}
