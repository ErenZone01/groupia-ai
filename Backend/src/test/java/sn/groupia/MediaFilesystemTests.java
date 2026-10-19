package sn.groupia;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sn.groupia.media.domain.model.MediaRendition;
import sn.groupia.media.domain.service.MediaStorageException;
import sn.groupia.media.infrastructure.storage.FilesystemMediaStorage;

class MediaFilesystemTests {
    @TempDir Path directory;

    @Test
    void putsChecksAndDeletesIdempotently() throws Exception {
        var storage = new FilesystemMediaStorage(directory.toString());
        UUID id = UUID.randomUUID();
        String key =
                "organizations/"
                        + UUID.randomUUID()
                        + "/products/"
                        + UUID.randomUUID()
                        + "/"
                        + id
                        + "/v1/display.jpg";
        byte[] bytes = MediaImageTests.image("jpeg", 3, 2, false);
        var rendition =
                new MediaRendition(
                        key, "image/jpeg", 3, 2, bytes.length, FilesystemMediaStorage.sha(bytes));
        storage.put(rendition, bytes);
        storage.put(rendition, bytes);
        assertThat(storage.read(key)).isEqualTo(bytes);
        assertThat(storage.head(key).orElseThrow().sha256()).isEqualTo(rendition.sha256());
        assertThat(storage.resolveUrl(key)).isEqualTo("/api/public/media/" + id + "/display");
        storage.delete(key);
        storage.delete(key);
        assertThat(storage.head(key)).isEmpty();
    }

    @Test
    void refusesTraversalAndArbitraryPaths() {
        var storage = new FilesystemMediaStorage(directory.toString());
        for (String key :
                java.util.List.of(
                        "../secret", "C:/secret", "image.jpg", "organizations/../../secret"))
            assertThatThrownBy(() -> storage.head(key)).isInstanceOf(MediaStorageException.class);
    }
}
