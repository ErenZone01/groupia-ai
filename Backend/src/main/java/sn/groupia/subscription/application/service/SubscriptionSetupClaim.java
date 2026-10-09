package sn.groupia.subscription.application.service;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.subscription.domain.repository.SubscriptionStore;

@Service
class SubscriptionSetupClaim {
    private final SubscriptionStore subscriptions;
    SubscriptionSetupClaim(SubscriptionStore subscriptions) { this.subscriptions = subscriptions; }

    @Transactional
    boolean claim(UUID organizationId, UUID key, Instant now) {
        return subscriptions.claimProviderSetup(organizationId, key, now);
    }

    @Transactional
    void save(UUID organizationId, UUID key, String provider, String customerId, String subscriptionId, Instant now) {
        subscriptions.saveProviderSetup(organizationId, key, provider, customerId, subscriptionId, now);
    }
}
