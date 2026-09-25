package sn.groupia.grouping.api.dto;
import java.math.BigDecimal;
import java.util.*;
public record CheckoutOffer(UUID offerId, int quantity, BigDecimal unitPrice, long priceRevision, long version,
        UUID productId, UUID productVariantId, String productName, String productDescription,
        String commercialLabel, Map<String, String> variantAttributes, String sku, String saleUnit,
        BigDecimal estimatedUnitWeightKg, BigDecimal estimatedUnitVolumeM3) {
    public CheckoutOffer { variantAttributes = Map.copyOf(variantAttributes); }
}
