package sn.groupia.shipping.api.dto;
import java.time.Instant; import java.util.UUID;
public record ShippingMeasurementView(UUID id,UUID assessmentId,MeasurementInput information,UUID actorId,Instant recordedAt) {}
