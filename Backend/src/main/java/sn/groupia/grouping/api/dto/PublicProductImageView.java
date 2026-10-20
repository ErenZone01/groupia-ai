package sn.groupia.grouping.api.dto;

/** Public commerce projection; contains no storage keys or internal media identifiers. */
public record PublicProductImageView(
        int position,
        String thumbnailUrl,
        String displayUrl,
        int width,
        int height) {}
