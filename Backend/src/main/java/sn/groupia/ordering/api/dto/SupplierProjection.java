package sn.groupia.ordering.api.dto;
import java.math.BigDecimal;
import java.util.*;
public record SupplierProjection(UUID groupingCampaignId, long acceptedOrderCount, long totalUnits,
        BigDecimal goodsAmount, String currency, List<SupplierProjectionLine> lines,
        List<UUID> acceptedOrderIds, long activePendingOrderCount) {
    public SupplierProjection { lines=List.copyOf(lines); acceptedOrderIds=List.copyOf(acceptedOrderIds); }
    public SupplierSummaryView summary() {
        return new SupplierSummaryView(groupingCampaignId,acceptedOrderCount,totalUnits,goodsAmount,currency,
                lines.stream().map(SupplierProjectionLine::snapshot).toList());
    }
}
