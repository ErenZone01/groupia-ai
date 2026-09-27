package sn.groupia.ordering.api.dto;

import java.math.BigDecimal;
import java.util.Map;

public record SupplierSummaryLine(
        String productNameSnapshot,
        String productDescriptionSnapshot,
        String commercialLabelSnapshot,
        Map<String, String> variantAttributesSnapshot,
        String skuSnapshot,
        String saleUnitSnapshot,
        long totalQuantity,
        BigDecimal goodsAmount) {
    public SupplierSummaryLine {
        variantAttributesSnapshot = Map.copyOf(variantAttributesSnapshot);
    }
}
