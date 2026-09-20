package sn.groupia.catalog.api.dto;
import java.math.BigDecimal;
import java.util.*;
public record CatalogVariantView(UUID productVariantId, String productName, String productDescription,
        Map<String, String> attributes, String saleUnit, BigDecimal estimatedUnitWeightKg,
        BigDecimal estimatedUnitVolumeM3, boolean usable) {}
