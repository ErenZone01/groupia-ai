package sn.groupia.ordering.domain.model;
import java.time.Instant;
import java.util.UUID;
public record FulfillmentEvent(UUID id,UUID organizationId,UUID campaignId,UUID orderId,Type type,UUID actorId,Instant recordedAt,String note){
    public enum Type { READY_FOR_PICKUP, READINESS_REVOKED, PICKED_UP }
}
