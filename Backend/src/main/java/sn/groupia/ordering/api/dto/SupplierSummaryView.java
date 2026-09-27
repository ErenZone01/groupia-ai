package sn.groupia.ordering.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SupplierSummaryView(
        UUID groupingCampaignId,
        long acceptedOrderCount,
        long totalUnits,
        BigDecimal goodsAmount,
        String currency,
        List<SupplierSummaryLine> lines) {
    public SupplierSummaryView {
        lines = List.copyOf(lines);
    }
}
