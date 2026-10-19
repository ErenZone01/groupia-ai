package sn.groupia.media.api.dto;

import java.util.UUID;

public record ProductMediaSummary(
        UUID productId,
        String primaryThumbnailUrl,
        String primaryDisplayUrl,
        int imageCount,
        long galleryVersion,
        boolean enabled) {}
