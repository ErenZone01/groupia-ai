package sn.groupia.shipping.domain.repository;
import java.util.*;
import sn.groupia.shipping.domain.model.Shipment;
import sn.groupia.shipping.api.dto.LogisticsFactView;
public interface ShipmentRepository {
    List<Shipment> findAll(Set<UUID> ids);
    Optional<Shipment> find(UUID org,UUID campaign);
    void insert(Shipment shipment);
    void update(Shipment shipment);
    void append(LogisticsFactView fact);
    List<LogisticsFactView> timeline(UUID org,UUID shipment);
}
