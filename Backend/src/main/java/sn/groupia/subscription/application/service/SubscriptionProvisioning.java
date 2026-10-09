package sn.groupia.subscription.application.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.organization.api.OrganizationSubscriptionContext;
import sn.groupia.subscription.domain.model.OrganizationSubscription;
import sn.groupia.subscription.domain.repository.SubscriptionStore;

@Service
class SubscriptionProvisioning {
    private final OrganizationSubscriptionContext organizations;
    private final SubscriptionStore subscriptions;
    private final SubscriptionSettings settings;
    private final Clock clock;

    SubscriptionProvisioning(OrganizationSubscriptionContext organizations, SubscriptionStore subscriptions,
            SubscriptionSettings settings, Clock clock) {
        this.organizations = organizations;
        this.subscriptions = subscriptions;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    OrganizationSubscription ensure(UUID organizationId) {
        var existing = subscriptions.find(organizationId);
        if (existing.isPresent()) return existing.get();

        var facts = organizations.subscriptionFacts(organizationId);
        Instant migrationAppliedAt = subscriptions.migrationAppliedAt().orElse(null);
        boolean legacy = migrationAppliedAt != null && !facts.createdAt().isAfter(migrationAppliedAt);
        Instant startedAt = legacy ? migrationAppliedAt : facts.createdAt();
        Instant endsAt = startedAt.atZone(ZoneId.of(facts.timezone()))
                .plusMonths(settings.getTrialMonths()).toInstant();
        Instant now = clock.instant();
        subscriptions.createTrial(organizationId, startedAt, endsAt,
                legacy ? "LEGACY_MIGRATION_TRIAL" : "ORGANIZATION_CREATED", now);
        return subscriptions.find(organizationId)
                .orElseThrow(() -> new IllegalStateException("Subscription trial provisioning did not persist."));
    }
}
