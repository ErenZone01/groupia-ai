package sn.groupia.media.api.dto;

/** Public-safe immutable product image projection. */
public record PublicProductImage(
        int position, String thumbnailUrl, String displayUrl, int width, int height) {}
