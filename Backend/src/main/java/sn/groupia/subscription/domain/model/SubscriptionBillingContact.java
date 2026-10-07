package sn.groupia.subscription.domain.model;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionBillingContact(
        UUID id,
        UUID organizationId,
        String name,
        String phoneE164,
        String email,
        String country,
        String locale,
        Instant updatedAt,
        UUID updatedBy,
        long version) {}
