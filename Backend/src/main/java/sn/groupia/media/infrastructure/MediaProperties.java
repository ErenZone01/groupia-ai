package sn.groupia.media.infrastructure;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("groupia.media")
public record MediaProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String storageType,
        @DefaultValue("") String publicBaseUrl,
        @DefaultValue(".groupia-media") String localRoot,
        @DefaultValue("8388608") int maxUploadBytes,
        @DefaultValue("6000") int maxWidth,
        @DefaultValue("6000") int maxHeight,
        @DefaultValue("24000000") long maxPixels,
        @DefaultValue("2000") int displayMaxSide,
        @DefaultValue("480") int thumbnailMaxSide,
        @DefaultValue("4194304") int displayMaxBytes,
        @DefaultValue("524288") int thumbnailMaxBytes,
        @DefaultValue("1") int processingConcurrency,
        @DefaultValue S3 s3) {
    public record S3(
            @DefaultValue("") String endpoint,
            @DefaultValue("") String region,
            @DefaultValue("") String bucket,
            @DefaultValue("") String accessKey,
            @DefaultValue("") String secretKey,
            @DefaultValue("false") boolean pathStyleAccess,
            @DefaultValue("false") boolean chunkedEncodingEnabled) {
        @Override
        public String toString() {
            return "MediaS3[credentials=REDACTED]";
        }
    }

    public void validate(boolean production) {
        if (!enabled) return;
        if (maxUploadBytes < 1024
                || maxUploadBytes > 8388608
                || maxWidth < 1
                || maxWidth > 6000
                || maxHeight < 1
                || maxHeight > 6000
                || maxPixels < 1
                || maxPixels > 24000000
                || displayMaxSide < 1
                || displayMaxSide > 2000
                || thumbnailMaxSide < 1
                || thumbnailMaxSide > 480
                || displayMaxBytes < 1024
                || displayMaxBytes > 4194304
                || thumbnailMaxBytes < 1024
                || thumbnailMaxBytes > 524288
                || processingConcurrency != 1) throw invalid();
        if (!storageType.equals("filesystem") && !storageType.equals("s3")) throw invalid();
        if (production && !storageType.equals("s3")) throw invalid();
        if (storageType.equals("filesystem")) {
            if (localRoot.isBlank()) throw invalid();
        } else {
            validateUrl(s3.endpoint, production);
            validateUrl(publicBaseUrl, true);
            if (s3.region.isBlank()
                    || !s3.bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                    || s3.accessKey.isBlank()
                    || s3.secretKey.isBlank()) throw invalid();
        }
    }

    static void validateUrl(String value, boolean https) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !(uri.getScheme().equals("https")
                            || (!https && uri.getScheme().equals("http")))) throw invalid();
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("Invalid media configuration (values redacted)");
    }

    @Override
    public String toString() {
        return "MediaProperties[enabled=" + enabled + ", credentials=REDACTED]";
    }
}
