package sn.groupia.ordering.api.dto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import sn.groupia.grouping.api.dto.GroupingProgress;
/** Tracking-only contract; checkout receipt and its one-time secret stay unchanged. */
public record OrderTrackingView(String orderNumber,String status,String fulfillmentStatus,
        BigDecimal goodsSubtotal,BigDecimal platformFeeAmount,BigDecimal estimatedShippingAmount,
        BigDecimal initialShippingChargeAmount,BigDecimal initialAmountDue,String currency,
        Instant paymentDeadline,Instant placedAt,List<OrderLineView> items,GroupingProgress groupingProgress,sn.groupia.shipping.api.dto.ShippingExplanation shippingFinal,ShippingSettlementView shippingSettlement) {
    public static OrderTrackingView from(OrderReceipt r,GroupingProgress progress,sn.groupia.shipping.api.dto.ShippingExplanation shippingFinal,ShippingSettlementView shippingSettlement) {
        return new OrderTrackingView(r.orderNumber(),r.status(),r.fulfillmentStatus(),r.goodsSubtotal(),r.platformFeeAmount(),
            r.estimatedShippingAmount(),r.initialShippingChargeAmount(),r.initialAmountDue(),r.currency(),r.paymentDeadline(),r.placedAt(),r.items(),progress,shippingFinal,shippingSettlement);
    }
}
