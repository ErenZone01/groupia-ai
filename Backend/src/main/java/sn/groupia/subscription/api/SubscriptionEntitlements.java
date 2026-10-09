package sn.groupia.subscription.api;

import java.util.UUID;

public interface SubscriptionEntitlements {
    SubscriptionAccessView access(UUID organizationId);
    void requireNewBusinessAllowed(UUID organizationId);
}
