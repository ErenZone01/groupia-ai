package sn.groupia.media.infrastructure.web;

import java.time.Duration;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import sn.groupia.media.domain.repository.MediaRecords;
import sn.groupia.media.domain.service.MediaObjectStorage;
import sn.groupia.media.infrastructure.storage.FilesystemMediaStorage;
import sn.groupia.shared.api.ApiException;

@RestController
class LocalMediaController {
    private final MediaRecords records;
    private final MediaObjectStorage storage;

    LocalMediaController(MediaRecords records, MediaObjectStorage storage) {
        this.records = records;
        this.storage = storage;
    }

    @GetMapping("/api/public/media/{mediaId}/{rendition}")
    ResponseEntity<byte[]> localProductImage(
            @PathVariable UUID mediaId, @PathVariable String rendition) {
        if (!(storage instanceof FilesystemMediaStorage local)
                || !(rendition.equals("display") || rendition.equals("thumbnail"))) throw missing();
        var asset = records.findPublic(mediaId).orElseThrow(this::missing);
        var image = rendition.equals("display") ? asset.display() : asset.thumbnail();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .contentLength(image.sizeBytes())
                .header("Content-Disposition", "inline")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .body(local.read(image.key()));
    }

    private ApiException missing() {
        return new ApiException(404, "MEDIA_NOT_FOUND", "Image introuvable.");
    }
}
