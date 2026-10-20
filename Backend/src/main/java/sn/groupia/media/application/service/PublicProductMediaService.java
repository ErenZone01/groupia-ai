package sn.groupia.media.application.service;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.media.api.PublicProductMediaQueries;
import sn.groupia.media.api.dto.PublicProductImage;
import sn.groupia.media.domain.model.ProductMedia;
import sn.groupia.media.domain.repository.MediaRecords;
import sn.groupia.media.domain.service.MediaObjectStorage;
import sn.groupia.media.domain.service.MediaStorageException;

@Service
@Transactional(readOnly = true)
public class PublicProductMediaService implements PublicProductMediaQueries {
    private final MediaRecords records;
    private final MediaObjectStorage storage;

    public PublicProductMediaService(MediaRecords records, MediaObjectStorage storage) {
        this.records = records;
        this.storage = storage;
    }

    @Override
    public Map<UUID, List<PublicProductImage>> readyImages(
            UUID organizationId, Set<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, List<PublicProductImage>> result = new HashMap<>();
        for (ProductMedia media : records.list(organizationId, Set.copyOf(productIds))) {
            if (media.state() != ProductMedia.State.READY || media.position() == null) {
                continue;
            }
            try {
                String thumbnailUrl = storage.resolveUrl(media.thumbnail().key());
                String displayUrl = storage.resolveUrl(media.display().key());
                result.computeIfAbsent(media.productId(), ignored -> new ArrayList<>())
                        .add(
                                new PublicProductImage(
                                        media.position(),
                                        thumbnailUrl,
                                        displayUrl,
                                        media.display().width(),
                                        media.display().height()));
            } catch (MediaStorageException ignored) {
                // Product pages must remain available if media delivery is temporarily disabled/unavailable.
            }
        }

        Map<UUID, List<PublicProductImage>> immutable = new HashMap<>();
        result.forEach(
                (productId, images) -> {
                    images.sort(Comparator.comparingInt(PublicProductImage::position));
                    immutable.put(productId, List.copyOf(images));
                });
        return Map.copyOf(immutable);
    }
}
