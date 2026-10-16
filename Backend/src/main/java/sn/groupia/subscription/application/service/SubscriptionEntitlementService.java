package sn.groupia.subscription.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.shared.api.ApiException;
import sn.groupia.subscription.api.SubscriptionAccessReason;
import sn.groupia.subscription.api.SubscriptionAccessView;
import sn.groupia.subscription.api.SubscriptionEntitlements;
import sn.groupia.subscription.api.SubscriptionStatus;
import sn.groupia.subscription.domain.repository.SubscriptionStore;

@Service
class SubscriptionEntitlementService implements SubscriptionEntitlements {
    private final SubscriptionProvisioning provisioning;
    private final SubscriptionStore subscriptions;
    private final SubscriptionSettings settings;
    private final Clock clock;

    SubscriptionEntitlementService(
            SubscriptionProvisioning provisioning,
            SubscriptionStore subscriptions,
            SubscriptionSettings settings,
            Clock clock) {
        this.provisioning = provisioning;
        this.subscriptions = subscriptions;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SubscriptionAccessView access(UUID organizationId) {
        provisioning.ensure(organizationId);
        var subscription = subscriptions.lock(organizationId).orElseThrow();
        var now = clock.instant();
        var periods = subscriptions.history(organizationId);
        boolean hasActivePaidPeriod =
                periods.stream()
                        .anyMatch(
                                period ->
                                        !now.isBefore(period.periodStart())
                                                && now.isBefore(period.periodEnd()));
        boolean trialActive = now.isBefore(subscription.trialEndsAt());
        var latest =
                periods.stream()
                        .filter(p -> !now.isBefore(p.periodStart()) && now.isBefore(p.periodEnd()))
                        .findFirst()
                        .orElse(null);

        SubscriptionStatus status;
        if ("SUSPENDED".equals(subscription.status())) {
            status = SubscriptionStatus.SUSPENDED;
        } else if (hasActivePaidPeriod) {
            status = SubscriptionStatus.ACTIVE;
        } else if (trialActive) {
            status = SubscriptionStatus.TRIALING;
        } else {
            status = SubscriptionStatus.PAST_DUE;
        }
        subscriptions.refreshStatus(organizationId, status.name(), now);

        SubscriptionAccessReason reason =
                switch (status) {
                    case TRIALING -> SubscriptionAccessReason.TRIAL_ACTIVE;
                    case ACTIVE -> SubscriptionAccessReason.SUBSCRIPTION_ACTIVE;
                    case SUSPENDED -> SubscriptionAccessReason.SUSPENDED;
                    case CANCELED -> SubscriptionAccessReason.CANCELED;
                    case PAST_DUE ->
                            periods.isEmpty()
                                    ? SubscriptionAccessReason.TRIAL_EXPIRED
                                    : SubscriptionAccessReason.PAST_DUE;
                };
        Instant currentPeriodStart = latest == null ? null : latest.periodStart();
        Instant currentPeriodEnd = latest == null ? null : latest.periodEnd();
        String planCode = latest == null ? null : latest.planCode();
        var planAmount = latest == null ? null : latest.amount();
        boolean canStart =
                status == SubscriptionStatus.TRIALING || status == SubscriptionStatus.ACTIVE;
        return new SubscriptionAccessView(
                organizationId,
                status,
                subscription.trialStartedAt(),
                subscription.trialEndsAt(),
                subscription.trialProvenance(),
                currentPeriodStart,
                currentPeriodEnd,
                planCode,
                planAmount,
                subscription.currency(),
                subscription.billingInterval(),
                false,
                canStart,
                reason);
    }

    @Override
    @Transactional
    public void requireNewBusinessAllowed(UUID organizationId) {
        if (!settings.isEnabled() || !settings.isEnforcementEnabled()) return;
        var access = access(organizationId);
        if (!access.canStartNewBusiness()) {
            throw new ApiException(
                    403,
                    "SUBSCRIPTION_REQUIRED",
                    "Un abonnement actif est requis pour créer ou publier un nouveau groupage.",
                    java.util.Map.of(
                            "reason",
                            access.reason().name(),
                            "subscriptionUrl",
                            "/app/abonnement"));
        }
    }
}
