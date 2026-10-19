package sn.groupia.media.domain.model;

public record MediaRendition(
        String key, String contentType, int width, int height, long sizeBytes, String sha256) {}
