package sn.groupia.shipping.application.service;
import java.math.*;
import java.util.List;
import org.springframework.stereotype.Service;
import sn.groupia.shipping.api.*;
import sn.groupia.shipping.api.dto.*;
import sn.groupia.shared.api.ApiException;

@Service
class DefaultShippingEstimator implements ShippingEstimator {
    @Override public BigDecimal estimate(ShippingTerms terms, List<ShippingEstimateLine> lines) {
        if (terms == null || lines == null || lines.isEmpty()) { throw unavailable(); }
        BigDecimal units = BigDecimal.ZERO;
        boolean missing = false;
        for (var line : lines) {
            if (line == null || line.quantity() < 1) {
                throw new ApiException(400, "INVALID_ORDER_QUANTITY", "La quantité doit être positive.");
            }
            if ((line.unitWeightKg() != null && line.unitWeightKg().signum() <= 0)
                    || (line.unitVolumeM3() != null && line.unitVolumeM3().signum() <= 0)) { throw unavailable(); }
            BigDecimal unit = switch (terms.shippingPricingMethod()) {
                case PER_KG -> line.unitWeightKg();
                case PER_CBM -> line.unitVolumeM3();
                case PER_ITEM, FIXED -> BigDecimal.ONE;
            };
            if (unit == null) { missing = true; }
            else { units = units.add(unit.multiply(BigDecimal.valueOf(line.quantity()))); }
        }
        if (missing) {
            if (terms.shippingCollectionMode() == ShippingCollectionMode.ESTIMATED_AT_CHECKOUT) { throw unavailable(); }
            return null;
        }
        if (terms.shippingPricingMethod() == ShippingPricingMethod.FIXED) { units = BigDecimal.ONE; }
        return units.multiply(terms.shippingRate()).setScale(0, RoundingMode.HALF_UP);
    }
    private ApiException unavailable() {
        return new ApiException(409, "SHIPPING_ESTIMATE_UNAVAILABLE", "Les données ne permettent pas une estimation du transport.");
    }
}
