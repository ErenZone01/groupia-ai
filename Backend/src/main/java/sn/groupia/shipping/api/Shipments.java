package sn.groupia.shipping.api;
import java.time.Instant;
import java.util.*;
import sn.groupia.shipping.api.dto.ShipmentView;
/** Grouping owns permission checks, campaign locking/version and the enclosing atomic transaction. */
public interface Shipments {
    Map<UUID,ShipmentView> findAll(Set<UUID> campaignIds);
    Optional<ShipmentView> find(UUID organizationId, UUID campaignId);
    ShipmentView depart(UUID org,UUID campaign,TransportMode mode,Instant departure,Instant expected,
        String reference,String note,LogisticsSource source,UUID actor,Instant now);
    ShipmentView arrive(UUID org,UUID campaign,Instant arrival,LogisticsSource source,String note,UUID actor,Instant now);
    ShipmentView enrich(UUID org,UUID campaign,TransportMode mode,String name,String contact,String reference,String note,
        LogisticsSource source,UUID actor,Instant now);
    ShipmentView estimate(UUID org,UUID campaign,TransportMode mode,Instant expected,LogisticsSource source,String note,UUID actor,Instant now);
    ShipmentView addFact(UUID org,UUID campaign,TransportMode mode,LogisticsFactType type,Instant occurredAt,
        LogisticsSource source,String note,UUID actor,Instant now);
}
