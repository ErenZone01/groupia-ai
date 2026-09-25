package sn.groupia.identity.api.event;

import java.time.Instant;
import java.util.UUID;

public record UserAccountCreated(UUID eventId, Instant occurredAt, UUID userAccountId) {}
