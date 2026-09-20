package sn.groupia.catalog.api.dto;
import java.util.UUID;
/** Internal module contract, never returned directly by the storefront. */
public record CatalogCommercialSnapshot(UUID productId, String sku, CatalogVariantView variant) {}
