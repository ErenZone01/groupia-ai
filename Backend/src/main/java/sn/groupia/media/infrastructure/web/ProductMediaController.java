package sn.groupia.media.infrastructure.web;

import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;
import sn.groupia.media.api.dto.*;
import sn.groupia.media.application.service.ProductMediaService;
import sn.groupia.shared.api.ApiException;

@RestController
@RequestMapping("/api/organizations/{organizationId}")
class ProductMediaController {
    private final ProductMediaService service;

    ProductMediaController(ProductMediaService service) {
        this.service = service;
    }

    @GetMapping("/products/{productId}/media")
    ResponseEntity<MediaGalleryView> mediaGallery(
            @PathVariable UUID organizationId, @PathVariable UUID productId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.gallery(organizationId, productId));
    }

    @GetMapping("/product-media")
    ResponseEntity<List<ProductMediaSummary>> productMediaBatch(
            @PathVariable UUID organizationId, @RequestParam List<UUID> productIds) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.batch(organizationId, productIds));
    }

    @GetMapping("/products/{productId}/media/{mediaId}")
    ResponseEntity<MediaView> mediaOperation(
            @PathVariable UUID organizationId,
            @PathVariable UUID productId,
            @PathVariable UUID mediaId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.operation(organizationId, productId, mediaId));
    }

    @PostMapping(
            value = "/products/{productId}/media",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<MediaView> uploadProductMedia(
            @PathVariable UUID organizationId,
            @PathVariable UUID productId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestPart("file") MultipartFile file,
            MultipartHttpServletRequest request)
            throws IOException {
        if (request.getMultiFileMap().values().stream().mapToInt(List::size).sum() != 1
                || file.isEmpty())
            throw new ApiException(
                    400, "MEDIA_ONE_FILE_REQUIRED", "Sélectionnez une seule image par envoi.");
        try (var stream = file.getInputStream()) {
            var result =
                    service.upload(
                            organizationId,
                            productId,
                            idempotencyKey,
                            stream,
                            file.getContentType());
            return ResponseEntity.status(result.state().equals("READY") ? 201 : 202)
                    .cacheControl(CacheControl.noStore())
                    .body(result);
        }
    }

    @DeleteMapping("/products/{productId}/media/{mediaId}")
    ResponseEntity<Void> deleteProductMedia(
            @PathVariable UUID organizationId,
            @PathVariable UUID productId,
            @PathVariable UUID mediaId) {
        service.delete(organizationId, productId, mediaId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    public record MediaOrder(long version, List<UUID> mediaIds) {}

    @PutMapping("/products/{productId}/media/order")
    ResponseEntity<MediaGalleryView> reorderProductMedia(
            @PathVariable UUID organizationId,
            @PathVariable UUID productId,
            @RequestBody MediaOrder order) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        service.reorder(
                                organizationId, productId, order.version(), order.mediaIds()));
    }
}
