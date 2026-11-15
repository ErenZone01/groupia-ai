package sn.groupia.ordering.api.event;

import java.time.Instant;
import java.util.UUID;

public record OrderCancellationRequested(
        UUID eventId,
        Instant occurredAt,
        UUID organizationId,
        UUID campaignId,
        UUID orderId,
        UUID actorId) {}
