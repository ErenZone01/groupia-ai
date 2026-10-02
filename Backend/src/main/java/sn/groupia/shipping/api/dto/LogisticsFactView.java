package sn.groupia.shipping.api.dto;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.shipping.api.*;
/** Private append-only snapshot. Legacy entries may have no recoverable actor. */
public record LogisticsFactView(UUID id, UUID organizationId, UUID shipmentId, long shipmentVersion,
        LogisticsFactType type, Instant occurredAt, Instant recordedAt, LogisticsSource source, UUID actorId,
        String note, Instant previousExpectedArrivalAt, Instant expectedArrivalAt,
        String providerName, String providerContact, String trackingReference, boolean legacy) {}
