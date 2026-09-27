package sn.groupia.procurement.api.dto;
import sn.groupia.grouping.api.dto.CampaignLifecycleView;
/** Immutable purchase + current operational metadata. Anomalies never change the purchase. */
public record SupplierOrderResponse(SupplierOrderView supplierOrder, CampaignLifecycleView campaign,
        long lateAcceptedOrderCount, boolean replayed) {}
