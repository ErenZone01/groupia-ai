package sn.groupia.organization.api.dto;

import java.time.Instant;
import java.util.UUID;

public record OrganizationSubscriptionFacts(UUID organizationId, String name, String timezone, Instant createdAt) {}
