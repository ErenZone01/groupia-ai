package sn.groupia.media.domain.repository;

import java.time.Instant;
import java.util.*;
import sn.groupia.media.domain.model.ProductMedia;

public interface MediaRecords {
    long lockGallery(UUID org, UUID product, Instant now);

    Map<UUID, Long> versions(UUID org, Set<UUID> products);

    List<ProductMedia> list(UUID org, Set<UUID> products);

    Optional<ProductMedia> find(UUID org, UUID product, UUID id);

    Optional<ProductMedia> findIdempotency(UUID org, UUID product, UUID key);

    Optional<ProductMedia> findPublic(UUID id);

    void insert(ProductMedia media);

    void ready(UUID id, Instant now);

    void removing(UUID id, Instant now, String reason);

    void order(UUID product, List<UUID> orderedIds, Instant now);

    Optional<CleanupClaim> claimCleanup(Instant now);

    void cleaned(UUID id, UUID token, Instant now);

    void retryCleanup(UUID id, UUID token, Instant now);

    record CleanupClaim(ProductMedia media, UUID token) {}
}
