package sn.groupia.subscription.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrganizationSubscription(
        UUID id,
        UUID organizationId,
        String status,
        Instant trialStartedAt,
        Instant trialEndsAt,
        String trialProvenance,
        Instant currentPeriodStartedAt,
        Instant currentPeriodEndsAt,
        String planCode,
        BigDecimal planAmount,
        String currency,
        String billingInterval,
        String provider,
        String providerCustomerId,
        String providerSubscriptionId,
        UUID providerSetupKey,
        Instant providerSetupRequestedAt,
        boolean cancelAtPeriodEnd,
        Instant canceledAt,
        long version,
        String billingTimezone) {
    public boolean providerSetupPending() {
        return providerSetupKey != null && providerSubscriptionId == null;
    }
}
