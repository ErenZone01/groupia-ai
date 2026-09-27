package sn.groupia.procurement.domain.repository;
import java.util.*;
import java.time.Instant;
import sn.groupia.procurement.domain.model.SupplierOrder;
public interface SupplierOrders {
    Optional<SupplierOrder> find(UUID org,UUID campaign);
    void insert(SupplierOrder order);
    long anomalyCount(UUID supplierOrderId);
    void flagLateAcceptance(UUID org,UUID campaign,UUID orderId,UUID eventId,Instant detectedAt);
}
