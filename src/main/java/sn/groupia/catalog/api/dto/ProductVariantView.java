package sn.groupia.catalog.api.dto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
public record ProductVariantView(UUID id, UUID organizationId, UUID productId, String sku,
        Map<String, String> attributes, String canonicalVariantKey, String saleUnit,
        BigDecimal estimatedUnitWeightKg, BigDecimal estimatedUnitVolumeM3,
        boolean active, Instant createdAt, Instant updatedAt) {}
