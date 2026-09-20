package sn.groupia.shipping.api.dto;

import java.math.*;
import java.util.*;
import sn.groupia.shipping.api.*;

/** Commercial terms only. This contract does not calculate or collect shipping charges. */
public record ShippingTerms(String originCountry, TransportMode transportMode,
        ShippingPricingMethod shippingPricingMethod, ShippingCollectionMode shippingCollectionMode,
        BigDecimal shippingRate, String shippingCurrency, String roundingRule) {
    public ShippingTerms {
        if (transportMode == null || shippingPricingMethod == null || shippingCollectionMode == null
                || shippingRate == null || !"XOF".equals(shippingCurrency) || !"HALF_UP".equals(roundingRule)) {
            throw new IllegalArgumentException("Complete XOF shipping terms are required.");
        }
        originCountry = originCountry == null || originCountry.isBlank() ? null : originCountry.strip().toUpperCase(Locale.ROOT);
        if (originCountry != null && !Set.of(Locale.getISOCountries()).contains(originCountry)) {
            throw new IllegalArgumentException("An ISO country code is required.");
        }
        shippingRate = shippingRate.stripTrailingZeros();
        if (shippingRate.signum() < 0 || (shippingRate.signum() == 0 && shippingPricingMethod != ShippingPricingMethod.FIXED)
                || shippingRate.scale() > 18 || shippingRate.precision() - shippingRate.scale() > 18
                || (shippingPricingMethod == ShippingPricingMethod.FIXED && shippingRate.scale() > 0)) {
            throw new IllegalArgumentException("Invalid shipping rate.");
        }
    }

    public boolean canEstimate(BigDecimal weightKg, BigDecimal volumeM3) {
        if (shippingCollectionMode == ShippingCollectionMode.AFTER_FINAL_ASSESSMENT) { return true; }
        return switch (shippingPricingMethod) {
            case PER_KG -> weightKg != null && weightKg.signum() > 0;
            case PER_CBM -> volumeM3 != null && volumeM3.signum() > 0;
            case PER_ITEM, FIXED -> true;
        };
    }
}
