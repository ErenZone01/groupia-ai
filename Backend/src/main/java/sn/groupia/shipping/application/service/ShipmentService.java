package sn.groupia.shipping.application.service;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.shipping.api.*;
import sn.groupia.shipping.api.dto.ShipmentView;
import sn.groupia.shipping.domain.model.Shipment;
import sn.groupia.shipping.domain.repository.ShipmentRepository;
import sn.groupia.shared.api.ApiException;
@Service
public class ShipmentService implements Shipments {
    private final ShipmentRepository shipments;
    ShipmentService(ShipmentRepository shipments){this.shipments=shipments;}
    @Transactional(readOnly=true)
    public Map<UUID,ShipmentView> findAll(Set<UUID> ids) {
        return shipments.findAll(ids).stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Shipment::campaignId,Shipment::view));
    }
    @Transactional(readOnly=true)
    public Optional<ShipmentView> find(UUID org,UUID campaign){return shipments.find(org,campaign).map(Shipment::view);}
    @Transactional(propagation=Propagation.MANDATORY)
    public ShipmentView depart(UUID org,UUID campaign,TransportMode mode,Instant departedAt,
            Instant expectedArrivalAt,String trackingReference,String note,Instant now) {
        var shipment=Shipment.depart(org,campaign,mode,departedAt,expectedArrivalAt,trackingReference,note,now);
        shipments.insert(shipment);
        return shipment.view();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public ShipmentView arrive(UUID org,UUID campaign,Instant arrivedAt,Instant now) {
        var shipment=shipments.find(org,campaign)
                .orElseThrow(()->new ApiException(409,"SHIPMENT_NOT_FOUND","Le départ n’a pas été enregistré."))
                .arrive(arrivedAt,now);
        shipments.recordArrival(shipment);
        return shipment.view();
    }
}
