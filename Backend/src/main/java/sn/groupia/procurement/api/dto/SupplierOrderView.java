package sn.groupia.procurement.api.dto;
import java.time.Instant;
import java.util.*;
import java.math.BigDecimal;
import sn.groupia.ordering.api.dto.SupplierProjectionLine;
public record SupplierOrderView(UUID id, UUID organizationId, UUID groupingCampaignId,
        long acceptedOrderCount, long totalUnits, BigDecimal goodsAmount, String currency,
        Instant orderedAt, List<SupplierProjectionLine> lines) {
    public SupplierOrderView { lines=List.copyOf(lines); }
}
