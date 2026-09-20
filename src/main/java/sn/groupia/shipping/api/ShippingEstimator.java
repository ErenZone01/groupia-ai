package sn.groupia.shipping.api;
import java.math.BigDecimal;
import java.util.List;
import sn.groupia.shipping.api.dto.*;
public interface ShippingEstimator {
    /** Null means an informative estimate is unavailable in AFTER_FINAL_ASSESSMENT mode. */
    BigDecimal estimate(ShippingTerms terms, List<ShippingEstimateLine> lines);
}
