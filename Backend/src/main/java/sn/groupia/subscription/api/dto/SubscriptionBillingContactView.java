package sn.groupia.subscription.api.dto;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionBillingContactView(
        String name,
        String phoneE164,
        String email,
        String country,
        String locale,
        Instant updatedAt,
        UUID updatedBy,
        long version) {}
