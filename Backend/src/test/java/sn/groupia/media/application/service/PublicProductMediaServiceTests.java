package sn.groupia.media.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import sn.groupia.media.domain.model.MediaRendition;
import sn.groupia.media.domain.model.ProductMedia;
import sn.groupia.media.domain.repository.MediaRecords;
import sn.groupia.media.domain.service.MediaObjectStorage;
import sn.groupia.media.domain.service.MediaStorageException;

class PublicProductMediaServiceTests {
    private final MediaRecords records = mock(MediaRecords.class);
    private final MediaObjectStorage storage = mock(MediaObjectStorage.class);
    private final PublicProductMediaService service = new PublicProductMediaService(records, storage);

    @Test
    void exposesOnlyReadyImagesInGalleryOrderWithoutStorageKeys() {
        UUID organizationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        ProductMedia readySecond = media(productId, ProductMedia.State.READY, 1, "second");
        ProductMedia uploading = media(productId, ProductMedia.State.UPLOADING, 2, "uploading");
        ProductMedia readyFirst = media(productId, ProductMedia.State.READY, 0, "first");

        when(records.list(organizationId, Set.of(productId)))
                .thenReturn(List.of(readySecond, uploading, readyFirst));
        when(storage.resolveUrl("first-thumb")).thenReturn("https://images.example/first-thumb");
        when(storage.resolveUrl("first-display")).thenReturn("https://images.example/first-display");
        when(storage.resolveUrl("second-thumb")).thenReturn("https://images.example/second-thumb");
        when(storage.resolveUrl("second-display")).thenReturn("https://images.example/second-display");

        var result = service.readyImages(organizationId, Set.of(productId));

        assertThat(result.get(productId))
                .extracting(image -> image.position())
                .containsExactly(0, 1);
        assertThat(result.get(productId).get(0).thumbnailUrl())
                .isEqualTo("https://images.example/first-thumb");
        assertThat(result.get(productId).get(0).displayUrl())
                .isEqualTo("https://images.example/first-display");
        verify(storage, never()).resolveUrl("uploading-thumb");
        verify(storage, never()).resolveUrl("uploading-display");
    }

    @Test
    void mediaDeliveryFailureDoesNotTakeDownPublicCommercePage() {
        UUID organizationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        ProductMedia ready = media(productId, ProductMedia.State.READY, 0, "ready");

        when(records.list(organizationId, Set.of(productId))).thenReturn(List.of(ready));
        when(storage.resolveUrl("ready-thumb")).thenThrow(new MediaStorageException());

        assertThat(service.readyImages(organizationId, Set.of(productId)))
                .doesNotContainKey(productId);
    }

    private static ProductMedia media(
            UUID productId, ProductMedia.State state, int position, String keyPrefix) {
        Instant now = Instant.parse("2026-10-20T00:00:00Z");
        return new ProductMedia(
                UUID.randomUUID(),
                UUID.randomUUID(),
                productId,
                state,
                position,
                rendition(keyPrefix + "-display", 1200, 900),
                rendition(keyPrefix + "-thumb", 480, 360),
                UUID.randomUUID(),
                "fingerprint",
                now,
                now.plusSeconds(600),
                0,
                null);
    }

    private static MediaRendition rendition(String key, int width, int height) {
        return new MediaRendition(key, "image/jpeg", width, height, 1234, "sha256");
    }
}
