package sn.groupia.ordering.api.dto;
import java.time.Instant;
import java.util.UUID;
/** Private operational history. Never included in customer tracking. */
public record FulfillmentEventView(UUID id,String type,UUID actorId,Instant recordedAt,String note){}
