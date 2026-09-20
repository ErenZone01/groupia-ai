package sn.groupia.organization.api.event;
import java.time.Instant;
import java.util.UUID;
public record OrganizationCreated(UUID eventId, Instant occurredAt, UUID organizationId, UUID ownerUserAccountId) {}
