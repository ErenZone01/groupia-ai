package sn.groupia.ordering.api.dto;
import java.time.Instant;
import java.util.*;
public record FulfillmentRow(UUID orderId,String orderNumber,String customerName,String status,String fulfillmentStatus,
    long version,Instant readyForPickupAt,Instant pickedUpAt,ShippingSettlementView shippingSettlement,
    String readinessBlocker,boolean canMarkReady,boolean canRevoke,boolean canPickUp,List<FulfillmentEventView> history){}
