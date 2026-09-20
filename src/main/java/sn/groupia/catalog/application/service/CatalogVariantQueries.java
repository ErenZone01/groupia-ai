package sn.groupia.catalog.application.service;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.catalog.api.CatalogVariants;
import sn.groupia.catalog.api.dto.CatalogVariantView;
import sn.groupia.catalog.domain.model.ProductStatus;
import sn.groupia.catalog.domain.repository.Products;

@Service
@Transactional(readOnly = true)
class CatalogVariantQueries implements CatalogVariants {
    private final Products products;
    CatalogVariantQueries(Products products) { this.products = products; }

    @Override
    public Map<UUID, sn.groupia.catalog.api.dto.CatalogCommercialSnapshot> commercialSnapshots(UUID organizationId, Set<UUID> variantIds) {
        var result = new HashMap<UUID, sn.groupia.catalog.api.dto.CatalogCommercialSnapshot>();
        products.findVariants(organizationId, variantIds).forEach(v -> {
            var p = v.getProduct();
            var view = new CatalogVariantView(v.getId(), p.getName(), p.getDescription(),
                    Map.copyOf(v.getAttributes()), v.getSaleUnit().name(), v.getEstimatedUnitWeightKg(),
                    v.getEstimatedUnitVolumeM3(), v.isActive() && p.getStatus() == ProductStatus.ACTIVE);
            result.put(v.getId(), new sn.groupia.catalog.api.dto.CatalogCommercialSnapshot(p.getId(), v.getSku(), view));
        });
        return Map.copyOf(result);
    }

    @Override
    public Map<UUID, CatalogVariantView> findByIds(UUID organizationId, Set<UUID> variantIds) {
        if (variantIds.isEmpty()) { return Map.of(); }
        var result = new HashMap<UUID, CatalogVariantView>();
        products.findVariants(organizationId, variantIds).forEach(variant -> {
            var product = variant.getProduct();
            result.put(variant.getId(), new CatalogVariantView(variant.getId(), product.getName(), product.getDescription(),
                    Map.copyOf(variant.getAttributes()), variant.getSaleUnit().name(), variant.getEstimatedUnitWeightKg(),
                    variant.getEstimatedUnitVolumeM3(), variant.isActive() && product.getStatus() == ProductStatus.ACTIVE));
        });
        return Map.copyOf(result);
    }
}
