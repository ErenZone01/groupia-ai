package sn.groupia.shipping.api;
import java.time.Instant;
import java.util.*;
import sn.groupia.shipping.api.dto.ShipmentView;
/** Grouping coordinates authorization, campaign locking and the enclosing atomic transaction. */
public interface Shipments {
    Optional<ShipmentView> find(UUID organizationId, UUID campaignId);
    ShipmentView depart(UUID organizationId, UUID campaignId, TransportMode mode, Instant departedAt,
            Instant expectedArrivalAt, String trackingReference, String note, Instant now);
    ShipmentView arrive(UUID organizationId, UUID campaignId, Instant arrivedAt, Instant now);
}
