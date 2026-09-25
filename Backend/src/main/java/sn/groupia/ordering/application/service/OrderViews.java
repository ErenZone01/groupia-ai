package sn.groupia.ordering.application.service;
import sn.groupia.ordering.api.dto.*;
import sn.groupia.ordering.domain.model.Order;
final class OrderViews {
    private OrderViews() {}
    static OrderReceipt receipt(Order o, String token) {
        return new OrderReceipt(o.getOrderNumber(), o.getStatus().name(), o.getFulfillmentStatus().name(),
                o.getGoodsSubtotal(), o.getPlatformFeeAmount(), o.getEstimatedShippingAmount(),
                o.getInitialShippingChargeAmount(), o.getInitialAmountDue(), o.getCurrency(),
                o.getPaymentDeadline(), o.getPlacedAt(), o.getItems().stream().map(i -> new OrderLineView(
                        i.getLineNumber(), i.getProductNameSnapshot(), i.getProductDescriptionSnapshot(),
                        i.getCommercialLabelSnapshot(), i.getVariantAttributesSnapshot(), i.getSkuSnapshot(),
                        i.getSaleUnitSnapshot(), i.getQuantity(), i.getUnitPriceSnapshot(), i.getLineTotal(),
                        i.getEstimatedUnitWeightKgSnapshot(), i.getEstimatedUnitVolumeM3Snapshot())).toList(), token);
    }
    static OrganizerOrderView organizer(Order o) {
        return new OrganizerOrderView(o.getId(), o.getOrganizationId(), o.getCampaignId(), o.getCustomerId(),
                o.getCustomerContactSnapshot(), receipt(o, null), o.getVersion());
    }
}
