package sn.groupia.ordering.api.event;
import java.time.Instant;
import java.util.UUID;
public record OrderCommerciallyAccepted(UUID eventId, Instant occurredAt, UUID organizationId, UUID campaignId, UUID orderId) {}
