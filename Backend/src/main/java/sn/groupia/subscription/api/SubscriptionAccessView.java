package sn.groupia.subscription.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SubscriptionAccessView(
        UUID organizationId,
        SubscriptionStatus status,
        Instant trialStartedAt,
        Instant trialEndsAt,
        String trialProvenance,
        Instant currentPeriodStartedAt,
        Instant currentPeriodEndsAt,
        String planCode,
        BigDecimal planAmount,
        String currency,
        String billingInterval,
        boolean cancelAtPeriodEnd,
        boolean canStartNewBusiness,
        SubscriptionAccessReason reason) {}
