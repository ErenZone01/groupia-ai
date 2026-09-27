package sn.groupia.procurement.domain.model;
import java.time.Instant;
import java.util.*;
import java.math.BigDecimal;
import sn.groupia.ordering.api.dto.*;
import sn.groupia.procurement.api.dto.SupplierOrderView;
import sn.groupia.shared.api.ApiException;
/** Immutable value aggregate, persisted as one insert-only document with scalar ownership keys. */
public record SupplierOrder(UUID id, UUID organizationId, UUID groupingCampaignId,
        long sourceCampaignVersion, UUID orderedBy, Instant orderedAt, long acceptedOrderCount,
        long totalUnits, BigDecimal goodsAmount, String currency,
        List<SupplierProjectionLine> lines, List<UUID> acceptedOrderIds) {
    public SupplierOrder {
        Objects.requireNonNull(id); Objects.requireNonNull(organizationId); Objects.requireNonNull(groupingCampaignId);
        Objects.requireNonNull(orderedBy); Objects.requireNonNull(orderedAt);
        lines=List.copyOf(lines); acceptedOrderIds=List.copyOf(acceptedOrderIds);
        if(acceptedOrderCount<1 || lines.isEmpty()) throw new ApiException(409,"SUPPLIER_ORDER_EMPTY","Aucune commande acceptée à commander.");
        if(!"XOF".equals(currency) || sourceCampaignVersion<0 || totalUnits<1 || goodsAmount.signum()<0
                || goodsAmount.stripTrailingZeros().scale()>0 || acceptedOrderCount!=new HashSet<>(acceptedOrderIds).size()
                || acceptedOrderCount!=acceptedOrderIds.size()
                || totalUnits!=lines.stream().mapToLong(l->l.snapshot().totalQuantity()).sum()
                || goodsAmount.compareTo(lines.stream().map(l->l.snapshot().goodsAmount()).reduce(BigDecimal.ZERO,BigDecimal::add))!=0)
            throw new IllegalArgumentException("Inconsistent supplier purchase");
        for(var l:lines) {
            Objects.requireNonNull(l.productId()); Objects.requireNonNull(l.productVariantId()); Objects.requireNonNull(l.groupingOfferId());
            if(l.snapshot().totalQuantity()<1 || l.snapshot().goodsAmount().signum()<0
                    || l.snapshot().goodsAmount().stripTrailingZeros().scale()>0) throw new IllegalArgumentException("Invalid supplier line");
        }
    }
    public static SupplierOrder capture(UUID org, long version, UUID actor, Instant at, SupplierProjection p) {
        return new SupplierOrder(UUID.randomUUID(),org,p.groupingCampaignId(),version,actor,at,p.acceptedOrderCount(),
            p.totalUnits(),p.goodsAmount(),p.currency(),p.lines(),p.acceptedOrderIds());
    }
    public SupplierOrderView view(){return new SupplierOrderView(id,organizationId,groupingCampaignId,acceptedOrderCount,totalUnits,goodsAmount,currency,orderedAt,lines);}
}
