package sn.groupia.shipping.api.dto;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.shipping.api.TransportMode;
/** Private organizer view. Never return it from customer tracking. */
public record ShipmentView(UUID id, UUID organizationId, UUID groupingCampaignId, TransportMode transportMode,
        Instant departedAt, Instant expectedArrivalAt, Instant arrivedAt, String trackingReference, String note,
        Instant createdAt, Instant updatedAt) {}
