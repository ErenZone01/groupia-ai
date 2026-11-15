package sn.groupia.grouping.api.event;

import java.time.Instant;
import java.util.UUID;

public record GroupingCancelled(
        UUID eventId, Instant occurredAt, UUID organizationId, UUID campaignId, UUID actorId) {}
