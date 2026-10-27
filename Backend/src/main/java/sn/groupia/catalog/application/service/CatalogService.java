package sn.groupia.catalog.application.service;

import java.time.Clock;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.catalog.api.dto.*;
import sn.groupia.catalog.domain.model.*;
import sn.groupia.catalog.domain.repository.Products;
import sn.groupia.organization.api.*;
import sn.groupia.shared.api.ApiException;

@Service
@Transactional(readOnly = true)
public class CatalogService {
    private final Products products;
    private final OrganizationAccess access;
    private final Clock clock;

    public CatalogService(Products products, OrganizationAccess access, Clock clock) {
        this.products = products;
        this.access = access;
        this.clock = clock;
    }

    @Transactional
    public ProductView create(UUID organizationId, String name, String description, List<VariantSpecification> variants) {
        access.requirePermission(organizationId, OrganizationPermission.MANAGE_CATALOG);
        return view(products.save(Product.create(organizationId, name, description, variants, clock.instant())));
    }

    public ProductPage list(UUID organizationId, String status, int page, int size) {
        access.requirePermission(organizationId, OrganizationPermission.VIEW_CATALOG);
        if (page < 0 || page > 1000000 || size < 1 || size > 100) {
            throw new ApiException(400, "INVALID_REQUEST", "Pagination invalide.");
        }
        ProductStatus filter = null;
        if (status != null) {
            try { filter = ProductStatus.valueOf(status); }
            catch (IllegalArgumentException ex) { throw new ApiException(400, "INVALID_REQUEST", "Statut de produit invalide."); }
        }
        var results = products.list(organizationId, filter, page, size);
        return new ProductPage(results.items().stream().map(product -> new ProductSummary(product.getId(),
                product.getOrganizationId(), product.getName(), product.getStatus().name(),
                product.getCreatedAt(), product.getUpdatedAt(), product.getVersion())).toList(),
                page, size, results.totalElements(), results.totalPages());
    }

    public ProductView get(UUID organizationId, UUID productId) {
        access.requirePermission(organizationId, OrganizationPermission.VIEW_CATALOG);
        return view(find(organizationId, productId));
    }

    @Transactional
    public ProductView update(UUID organizationId, UUID productId, long version, String name, String description) {
        return change(organizationId, productId, version, product -> product.updateInformation(name, description, clock.instant()));
    }

    @Transactional
    public ProductView archive(UUID organizationId, UUID productId, long version) {
        return change(organizationId, productId, version, product -> product.archive(clock.instant()));
    }

    @Transactional
    public ProductView restore(UUID organizationId, UUID productId, long version) {
        return change(
                organizationId, productId, version, product -> product.restore(clock.instant()));
    }

    @Transactional
    public ProductView addVariant(UUID organizationId, UUID productId, long version, VariantSpecification specification) {
        return change(organizationId, productId, version, product -> product.addVariant(specification, clock.instant()));
    }

    @Transactional
    public ProductView updateVariant(UUID organizationId, UUID productId, UUID variantId, long version, VariantSpecification specification) {
        return change(organizationId, productId, version, product -> product.updateVariant(variantId, specification, clock.instant()));
    }

    @Transactional
    public ProductView deactivateVariant(UUID organizationId, UUID productId, UUID variantId, long version) {
        return change(organizationId, productId, version, product -> product.deactivateVariant(variantId, clock.instant()));
    }

    private ProductView change(UUID organizationId, UUID productId, long version, Consumer<Product> change) {
        access.requirePermission(organizationId, OrganizationPermission.MANAGE_CATALOG);
        Product product = find(organizationId, productId);
        product.requireVersion(version);
        change.accept(product);
        return view(products.save(product));
    }

    private Product find(UUID organizationId, UUID productId) {
        return products.find(organizationId, productId)
                .orElseThrow(() -> new ApiException(404, "PRODUCT_NOT_FOUND", "Produit introuvable dans cette organisation."));
    }

    private ProductView view(Product product) {
        return new ProductView(product.getId(), product.getOrganizationId(), product.getName(), product.getDescription(),
                product.getStatus().name(), product.getCreatedAt(), product.getUpdatedAt(), product.getVersion(),
                product.getVariants().stream().map(variant -> new ProductVariantView(variant.getId(),
                        variant.getOrganizationId(), product.getId(), variant.getSku(), variant.getAttributes(),
                        variant.getCanonicalVariantKey(), variant.getSaleUnit().name(),
                        variant.getEstimatedUnitWeightKg(), variant.getEstimatedUnitVolumeM3(),
                        variant.isActive(), variant.getCreatedAt(), variant.getUpdatedAt())).toList());
    }
}
