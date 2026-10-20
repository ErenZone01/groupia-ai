package sn.groupia.media.api.dto;

import java.util.*;

public record MediaGalleryView(
        UUID productId,
        long version,
        boolean enabled,
        boolean canManage,
        List<MediaView> items,
        int reservedCount) {}
