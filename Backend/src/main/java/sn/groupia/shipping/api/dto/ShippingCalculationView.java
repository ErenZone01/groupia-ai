package sn.groupia.shipping.api.dto;
import java.time.Instant; import java.util.UUID;
public record ShippingCalculationView(UUID id,UUID assessmentId,long revision,String kind,UUID measurementId,
    ShippingExplanation explanation,UUID actorId,Instant occurredAt,Instant recordedAt,String note,
    UUID supersedesCalculationId,String correctionReason) {}
