package sn.groupia.catalog.infrastructure.web;

import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.catalog.api.dto.*;
import sn.groupia.catalog.application.service.CatalogService;
import sn.groupia.catalog.domain.model.VariantSpecification;

@RestController
@RequestMapping("/api/organizations/{organizationId}/products")
class CatalogController {
    private final CatalogService catalog;
    CatalogController(CatalogService catalog) { this.catalog = catalog; }

    @PostMapping
    ResponseEntity<ProductView> create(@PathVariable UUID organizationId, @Valid @RequestBody CreateProduct request) {
        var product = catalog.create(organizationId, request.name(), request.description(),
                request.variants() == null ? List.of() : request.variants().stream().map(VariantInput::specification).toList());
        return ResponseEntity.created(URI.create("/api/organizations/" + organizationId + "/products/" + product.id())).body(product);
    }

    @GetMapping
    ProductPage list(@PathVariable UUID organizationId, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return catalog.list(organizationId, status, page, size);
    }

    @GetMapping("/{productId}")
    ProductView get(@PathVariable UUID organizationId, @PathVariable UUID productId) {
        return catalog.get(organizationId, productId);
    }

    @PutMapping("/{productId}")
    ProductView update(@PathVariable UUID organizationId, @PathVariable UUID productId, @Valid @RequestBody UpdateProduct request) {
        return catalog.update(organizationId, productId, request.version(), request.name(), request.description());
    }

    @PostMapping("/{productId}/archive")
    ProductView archive(@PathVariable UUID organizationId, @PathVariable UUID productId, @Valid @RequestBody VersionInput request) {
        return catalog.archive(organizationId, productId, request.version());
    }

    @PostMapping("/{productId}/restore")
    ProductView restore(
            @PathVariable UUID organizationId,
            @PathVariable UUID productId,
            @Valid @RequestBody VersionInput request) {
        return catalog.restore(organizationId, productId, request.version());
    }

    @PostMapping("/{productId}/variants")
    ProductView addVariant(@PathVariable UUID organizationId, @PathVariable UUID productId, @Valid @RequestBody VariantChange request) {
        return catalog.addVariant(organizationId, productId, request.version(), request.variant().specification());
    }

    @PutMapping("/{productId}/variants/{variantId}")
    ProductView updateVariant(@PathVariable UUID organizationId, @PathVariable UUID productId,
            @PathVariable UUID variantId, @Valid @RequestBody VariantChange request) {
        return catalog.updateVariant(organizationId, productId, variantId, request.version(), request.variant().specification());
    }

    @PostMapping("/{productId}/variants/{variantId}/deactivate")
    ProductView deactivateVariant(@PathVariable UUID organizationId, @PathVariable UUID productId,
            @PathVariable UUID variantId, @Valid @RequestBody VersionInput request) {
        return catalog.deactivateVariant(organizationId, productId, variantId, request.version());
    }

    record CreateProduct(@NotBlank @Size(max = 160) String name, @Size(max = 5000) String description,
            @Size(max = 100) List<@NotNull @Valid VariantInput> variants) {}
    record UpdateProduct(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 160) String name,
            @Size(max = 5000) String description) {}
    record VersionInput(@NotNull @PositiveOrZero Long version) {}
    record VariantChange(@NotNull @PositiveOrZero Long version, @NotNull @Valid VariantInput variant) {}
    record VariantInput(@Size(max = 128) String sku, Map<String, Object> attributes, String saleUnit,
            BigDecimal estimatedUnitWeightKg, BigDecimal estimatedUnitVolumeM3) {
        VariantSpecification specification() {
            return VariantSpecification.from(sku, attributes, saleUnit, estimatedUnitWeightKg, estimatedUnitVolumeM3);
        }
    }
}
