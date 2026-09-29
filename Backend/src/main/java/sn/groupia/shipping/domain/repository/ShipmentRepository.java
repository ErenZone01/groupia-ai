package sn.groupia.shipping.domain.repository;
import java.util.*;
import sn.groupia.shipping.domain.model.Shipment;
public interface ShipmentRepository {
    List<Shipment> findAll(Set<UUID> ids);
    Optional<Shipment> find(UUID org,UUID campaign);
    void insert(Shipment shipment);
    void recordArrival(Shipment shipment);
}
