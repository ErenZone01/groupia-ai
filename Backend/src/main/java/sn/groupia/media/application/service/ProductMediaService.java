package sn.groupia.media.application.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.InputStream;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sn.groupia.catalog.api.CatalogProducts;
import sn.groupia.media.api.dto.*;
import sn.groupia.media.domain.model.*;
import sn.groupia.media.domain.repository.MediaRecords;
import sn.groupia.media.domain.service.*;
import sn.groupia.media.infrastructure.MediaProperties;
import sn.groupia.media.infrastructure.image.ImageNormalizer;
import sn.groupia.organization.api.*;
import sn.groupia.shared.api.ApiException;

@Service
public class ProductMediaService {
    private final MediaRecords records;
    private final CatalogProducts products;
    private final OrganizationAccess access;
    private final MediaObjectStorage storage;
    private final ImageNormalizer images;
    private final MediaProperties properties;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final Clock clock;
    private final MeterRegistry metrics;

    public ProductMediaService(
            MediaRecords records,
            CatalogProducts products,
            OrganizationAccess access,
            MediaObjectStorage storage,
            ImageNormalizer images,
            MediaProperties properties,
            PlatformTransactionManager transactions,
            Clock clock,
            MeterRegistry metrics) {
        this.records = records;
        this.products = products;
        this.access = access;
        this.storage = storage;
        this.images = images;
        this.properties = properties;
        tx = new TransactionTemplate(transactions);
        this.clock = clock;
        this.metrics = metrics;
        readTx = new TransactionTemplate(transactions);
        readTx.setReadOnly(true);
        readTx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    private void enabled() {
        if (!properties.enabled())
            throw new ApiException(
                    503, "MEDIA_DISABLED", "Les images ne sont pas encore disponibles.");
    }

    private void authorize(UUID org, UUID product, OrganizationPermission permission) {
        access.requirePermission(org, permission);
        products.requireProducts(org, Set.of(product));
    }

    private void lock(UUID org, UUID product, boolean active) {
        products.requireProductForMutation(org, product, active);
        records.lockGallery(org, product, clock.instant());
    }

    public MediaGalleryView gallery(UUID org, UUID product) {
        authorize(org, product, OrganizationPermission.VIEW_CATALOG);
        return readTx.execute(
                s -> {
                    var items = records.list(org, Set.of(product));
                    return new MediaGalleryView(
                            product,
                            records.versions(org, Set.of(product)).getOrDefault(product, 0L),
                            properties.enabled(),
                            access.hasPermission(org, OrganizationPermission.MANAGE_CATALOG),
                            items.stream()
                                    .filter(m -> m.state() == ProductMedia.State.READY)
                                    .map(this::view)
                                    .toList(),
                            (int)
                                    items.stream()
                                            .filter(m -> m.state() == ProductMedia.State.UPLOADING)
                                            .count());
                });
    }

    public List<ProductMediaSummary> batch(UUID org, List<UUID> ids) {
        access.requirePermission(org, OrganizationPermission.VIEW_CATALOG);
        if (ids.isEmpty() || ids.size() > 100 || new HashSet<>(ids).size() != ids.size())
            throw new ApiException(400, "INVALID_REQUEST", "Sélection de produits invalide.");
        var set = Set.copyOf(ids);
        products.requireProducts(org, set);
        return readTx.execute(
                s -> {
                    var media =
                            records.list(org, set).stream()
                                    .filter(m -> m.state() == ProductMedia.State.READY)
                                    .toList();
                    var versions = records.versions(org, set);
                    return ids.stream()
                            .map(
                                    id -> {
                                        var items =
                                                media.stream()
                                                        .filter(m -> m.productId().equals(id))
                                                        .toList();
                                        ProductMedia first =
                                                items.isEmpty() ? null : items.getFirst();
                                        return new ProductMediaSummary(
                                                id,
                                                first == null || !properties.enabled()
                                                        ? null
                                                        : storage.resolveUrl(
                                                                first.thumbnail().key()),
                                                first == null || !properties.enabled()
                                                        ? null
                                                        : storage.resolveUrl(first.display().key()),
                                                items.size(),
                                                versions.getOrDefault(id, 0L),
                                                properties.enabled());
                                    })
                            .toList();
                });
    }

    public MediaView operation(UUID org, UUID product, UUID id) {
        authorize(org, product, OrganizationPermission.VIEW_CATALOG);
        return view(find(org, product, id));
    }

    public MediaView upload(
            UUID org, UUID product, UUID idempotency, InputStream source, String type) {
        enabled();
        authorize(org, product, OrganizationPermission.MANAGE_CATALOG);
        tx.executeWithoutResult(s -> products.requireProductForMutation(org, product, true));
        long started = System.nanoTime();
        ImageNormalizer.Normalized normalized;
        try {
            normalized = images.normalize(source, type);
        } catch (RuntimeException ex) {
            metrics.counter("groupia.media.upload.failure").increment();
            throw ex;
        } finally {
            metrics.timer("groupia.media.processing.duration")
                    .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        ProductMedia reservation =
                tx.execute(
                        s -> {
                            access.requirePermission(org, OrganizationPermission.MANAGE_CATALOG);
                            lock(org, product, true);
                            var current = records.list(org, Set.of(product));
                            var existing = records.findIdempotency(org, product, idempotency);
                            if (existing.isPresent()) {
                                if (!existing.get().fingerprint().equals(normalized.fingerprint()))
                                    throw new ApiException(
                                            409,
                                            "MEDIA_IDEMPOTENCY_CONFLICT",
                                            "Cette opération correspond à une autre image.");
                                return existing.get();
                            }
                            var active = current.stream().filter(this::occupies).toList();
                            if (active.size() >= 5)
                                throw new ApiException(
                                        409,
                                        "MEDIA_LIMIT_REACHED",
                                        "Un produit peut contenir cinq images maximum.");
                            String prefix =
                                    "organizations/"
                                            + org
                                            + "/products/"
                                            + product
                                            + "/"
                                            + id
                                            + "/v1/";
                            var m =
                                    new ProductMedia(
                                            id,
                                            org,
                                            product,
                                            ProductMedia.State.UPLOADING,
                                            active.size(),
                                            rendition(prefix, "display", normalized.display()),
                                            rendition(prefix, "thumbnail", normalized.thumbnail()),
                                            idempotency,
                                            normalized.fingerprint(),
                                            now,
                                            now.plusSeconds(900),
                                            0,
                                            null);
                            records.insert(m);
                            compact(org, product);
                            return m;
                        });
        if (!reservation.id().equals(id)) return view(reservation);
        boolean complete = false;
        try {
            storage.put(reservation.display(), normalized.display().bytes());
            storage.put(reservation.thumbnail(), normalized.thumbnail().bytes());
            complete = true;
        } catch (MediaStorageException ex) {
            metrics.counter("groupia.media.upload.failure").increment();
            try {
                complete = present(reservation.display()) && present(reservation.thumbnail());
            } catch (MediaStorageException ignored) {
                /* uncertain: persisted reservation remains recoverable */
            }
        }
        if (complete) {
            try {
                tx.executeWithoutResult(
                        s -> {
                            access.requirePermission(org, OrganizationPermission.MANAGE_CATALOG);
                            lock(org, product, true);
                            var current = find(org, product, id);
                            if (current.state() == ProductMedia.State.UPLOADING
                                    && clock.instant().isBefore(current.uploadExpiresAt())) {
                                records.ready(id, clock.instant());
                                compact(org, product);
                            }
                        });
            } catch (ApiException ex) {
                tx.executeWithoutResult(
                        s -> {
                            lock(org, product, false);
                            records.removing(id, clock.instant(), "MEDIA_PUBLICATION_REJECTED");
                            compact(org, product);
                        });
                throw ex;
            } catch (RuntimeException ex) {
                // Commit may have succeeded. Never compensate without reading the durable state.
                var current = find(org, product, id);
                if (current.state() == ProductMedia.State.READY) return view(current);
                return view(current);
            }
        }
        var result = find(org, product, id);
        if (result.state() == ProductMedia.State.READY)
            metrics.counter("groupia.media.upload.success").increment();
        return view(result);
    }

    private boolean present(MediaRendition r) {
        return storage.head(r.key())
                .filter(h -> h.sizeBytes() == r.sizeBytes() && r.sha256().equals(h.sha256()))
                .isPresent();
    }

    public void delete(UUID org, UUID product, UUID id) {
        enabled();
        authorize(org, product, OrganizationPermission.MANAGE_CATALOG);
        tx.executeWithoutResult(
                s -> {
                    lock(org, product, false);
                    var m = find(org, product, id);
                    if (occupies(m)) {
                        records.removing(id, clock.instant(), null);
                        compact(org, product);
                    }
                });
    }

    public MediaGalleryView reorder(UUID org, UUID product, long version, List<UUID> ids) {
        enabled();
        authorize(org, product, OrganizationPermission.MANAGE_CATALOG);
        tx.executeWithoutResult(
                s -> {
                    products.requireProductForMutation(org, product, true);
                    long current = records.lockGallery(org, product, clock.instant());
                    if (version != current)
                        throw new ApiException(
                                409,
                                "MEDIA_GALLERY_VERSION_CONFLICT",
                                "La galerie a changé. Rechargez-la.");
                    var all = records.list(org, Set.of(product));
                    Set<UUID> ready =
                            new HashSet<>(
                                    all.stream()
                                            .filter(m -> m.state() == ProductMedia.State.READY)
                                            .map(ProductMedia::id)
                                            .toList());
                    if (ids == null
                            || ids.size() > 5
                            || new HashSet<>(ids).size() != ids.size()
                            || !ready.equals(new HashSet<>(ids)))
                        throw new ApiException(
                                400,
                                "MEDIA_INVALID_ORDER",
                                "L’ordre doit contenir exactement les images de cette galerie.");
                    var order = new ArrayList<>(ids);
                    all.stream()
                            .filter(m -> m.state() == ProductMedia.State.UPLOADING)
                            .map(ProductMedia::id)
                            .forEach(order::add);
                    records.order(product, order, clock.instant());
                });
        return gallery(org, product);
    }

    private boolean occupies(ProductMedia m) {
        return m.state() == ProductMedia.State.READY || m.state() == ProductMedia.State.UPLOADING;
    }

    private void compact(UUID org, UUID product) {
        var all = records.list(org, Set.of(product));
        var ordered = new ArrayList<UUID>();
        all.stream()
                .filter(m -> m.state() == ProductMedia.State.READY)
                .map(ProductMedia::id)
                .forEach(ordered::add);
        all.stream()
                .filter(m -> m.state() == ProductMedia.State.UPLOADING)
                .map(ProductMedia::id)
                .forEach(ordered::add);
        records.order(product, ordered, clock.instant());
    }

    private ProductMedia find(UUID org, UUID product, UUID id) {
        return records.find(org, product, id)
                .orElseThrow(() -> new ApiException(404, "MEDIA_NOT_FOUND", "Image introuvable."));
    }

    private MediaView view(ProductMedia m) {
        boolean ready = m.state() == ProductMedia.State.READY && properties.enabled();
        return new MediaView(
                m.id(),
                m.state().name(),
                m.position(),
                ready ? storage.resolveUrl(m.display().key()) : null,
                ready ? storage.resolveUrl(m.thumbnail().key()) : null,
                m.display().contentType(),
                m.display().width(),
                m.display().height(),
                m.display().sizeBytes(),
                m.createdAt(),
                m.lastErrorCode());
    }

    private MediaRendition rendition(String prefix, String name, ImageNormalizer.Rendition r) {
        return new MediaRendition(
                prefix + name + (r.contentType().equals("image/png") ? ".png" : ".jpg"),
                r.contentType(),
                r.width(),
                r.height(),
                r.bytes().length,
                r.sha256());
    }

    /** Bounded, leased work. Every storage operation occurs after the claim transaction commits. */
    public void cleanup() {
        if (!properties.enabled()) return;
        for (int i = 0; i < 100; i++) {
            var claimed = tx.execute(s -> records.claimCleanup(clock.instant()));
            if (claimed.isEmpty()) return;
            var claim = claimed.get();
            var m = claim.media();
            try {
                boolean remove =
                        tx.execute(
                                s -> {
                                    lock(m.organizationId(), m.productId(), false);
                                    var current = find(m.organizationId(), m.productId(), m.id());
                                    if (current.state() == ProductMedia.State.UPLOADING
                                            && !clock.instant()
                                                    .isBefore(current.uploadExpiresAt())) {
                                        records.removing(
                                                m.id(), clock.instant(), "MEDIA_UPLOAD_EXPIRED");
                                        compact(m.organizationId(), m.productId());
                                        return true;
                                    }
                                    return current.state() == ProductMedia.State.DELETE_PENDING
                                            || current.state() == ProductMedia.State.DELETED;
                                });
                if (!remove) continue;
                storage.delete(m.display().key());
                storage.delete(m.thumbnail().key());
                tx.executeWithoutResult(
                        s -> records.cleaned(m.id(), claim.token(), clock.instant()));
            } catch (RuntimeException ex) {
                metrics.counter("groupia.media.cleanup.failure").increment();
                tx.executeWithoutResult(
                        s -> records.retryCleanup(m.id(), claim.token(), clock.instant()));
            }
        }
    }
}
