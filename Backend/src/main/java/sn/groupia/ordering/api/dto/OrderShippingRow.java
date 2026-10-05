package sn.groupia.ordering.api.dto;
import java.util.UUID;
import sn.groupia.shipping.api.dto.ShippingAssessmentView;
public record OrderShippingRow(UUID orderId,String orderNumber,String customerName,ShippingAssessmentView assessment,ShippingSettlementView shippingSettlement,String fulfillmentStatus) {}
