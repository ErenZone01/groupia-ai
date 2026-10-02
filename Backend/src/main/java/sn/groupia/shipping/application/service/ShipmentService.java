package sn.groupia.shipping.application.service;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.shipping.api.*;
import sn.groupia.shipping.api.dto.*;
import sn.groupia.shipping.domain.model.Shipment;
import sn.groupia.shipping.domain.repository.ShipmentRepository;
import sn.groupia.shared.api.ApiException;
@Service
public class ShipmentService implements Shipments {
    private final ShipmentRepository shipments;
    ShipmentService(ShipmentRepository shipments){this.shipments=shipments;}
    @Transactional(readOnly=true)
    public Map<UUID,ShipmentView> findAll(Set<UUID> ids) {
        // Batch customer projection does not need the private timeline.
        return shipments.findAll(ids).stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Shipment::campaignId,s->s.view(List.of())));
    }
    @Transactional(readOnly=true)
    public Optional<ShipmentView> find(UUID org,UUID campaign){return shipments.find(org,campaign).map(this::view);}
    @Transactional(propagation=Propagation.MANDATORY)
    public ShipmentView depart(UUID org,UUID campaign,TransportMode mode,Instant departure,Instant expected,
            String reference,String note,LogisticsSource source,UUID actor,Instant now) {
        var old=open(org,campaign,mode,now);
        return save(old,old.depart(departure,expected,reference,note,now),LogisticsFactType.DEPARTURE_CONFIRMED,departure,source,note,actor);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public ShipmentView arrive(UUID org,UUID campaign,Instant arrival,LogisticsSource source,String note,UUID actor,Instant now) {
        var old=shipments.find(org,campaign).orElseThrow(()->new ApiException(409,"SHIPMENT_NOT_FOUND","Le départ n’a pas été enregistré."));
        return save(old,old.arrive(arrival,now),LogisticsFactType.ARRIVAL_CONFIRMED,arrival,source,note,actor);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public ShipmentView enrich(UUID org,UUID campaign,TransportMode mode,String name,String contact,String reference,String note,
            LogisticsSource source,UUID actor,Instant now) {
        var old=open(org,campaign,mode,now);
        return save(old,old.enrich(name,contact,reference,note,now),LogisticsFactType.DOSSIER_UPDATED,null,source,note,actor);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public ShipmentView estimate(UUID org,UUID campaign,TransportMode mode,Instant expected,LogisticsSource source,String note,UUID actor,Instant now) {
        var old=open(org,campaign,mode,now);
        return save(old,old.estimate(expected,now),LogisticsFactType.ETA_UPDATED,null,source,note,actor);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public ShipmentView addFact(UUID org,UUID campaign,TransportMode mode,LogisticsFactType type,Instant occurredAt,
            LogisticsSource source,String note,UUID actor,Instant now) {
        if(type==null || !type.supplemental() || (type==LogisticsFactType.INFORMATION && Shipment.clean(note,2000)==null))
            throw new ApiException(400,"INVALID_LOGISTICS_FACT","Choisissez une information logistique et précisez la note si nécessaire.");
        Shipment.validateActual(occurredAt,now);
        var old=open(org,campaign,mode,now);
        return save(old,old.record(now),type,occurredAt,source,note,actor);
    }
    private Shipment open(UUID org,UUID campaign,TransportMode mode,Instant now) {
        return shipments.find(org,campaign).orElseGet(()->{var s=Shipment.open(org,campaign,mode,now);shipments.insert(s);return s;});
    }
    private ShipmentView save(Shipment old,Shipment s,LogisticsFactType type,Instant occurredAt,LogisticsSource source,String note,UUID actor) {
        var fact=new LogisticsFactView(UUID.randomUUID(),s.organizationId(),s.id(),s.version(),type,
            occurredAt==null?null:occurredAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS),s.updatedAt(),
            source==null?LogisticsSource.UNKNOWN:source,Objects.requireNonNull(actor),Shipment.clean(note,2000),
            old.expectedArrivalAt(),s.expectedArrivalAt(),s.providerName(),s.providerContact(),s.trackingReference(),false);
        shipments.update(s);shipments.append(fact);
        return view(s);
    }
    private ShipmentView view(Shipment s){return s.view(shipments.timeline(s.organizationId(),s.id()));}
}
