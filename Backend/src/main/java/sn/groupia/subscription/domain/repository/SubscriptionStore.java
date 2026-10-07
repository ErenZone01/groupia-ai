package sn.groupia.subscription.domain.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import sn.groupia.subscription.domain.model.OrganizationSubscription;
import sn.groupia.subscription.domain.model.SubscriptionBillingContact;
import sn.groupia.subscription.domain.model.SubscriptionPeriod;

public interface SubscriptionStore {
    Optional<OrganizationSubscription> find(UUID organizationId);
    Optional<OrganizationSubscription> lock(UUID organizationId);
    void createTrial(UUID organizationId, Instant startedAt, Instant endsAt, String provenance, Instant now);
    Optional<Instant> migrationAppliedAt();
    boolean claimProviderSetup(UUID organizationId, UUID setupKey, Instant requestedAt);
    void saveProviderSetup(UUID organizationId, UUID setupKey, String provider,
            String providerCustomerId, String providerSubscriptionId, Instant now);
    Optional<SubscriptionBillingContact> findBillingContact(UUID organizationId);
    void saveBillingContact(UUID organizationId, String name, String phoneE164, String email,
            UUID actorId, Instant now);
    List<SubscriptionPeriod> history(UUID organizationId);
    Optional<SubscriptionPeriod> latestPaidPeriod(UUID organizationId);
    boolean insertPaidPeriod(SubscriptionPeriod period);
    void updatePaidSubscription(UUID organizationId, String planCode, BigDecimal amount,
            Instant periodStart, Instant periodEnd, String provider, Instant now);
    void scheduleCancellation(UUID organizationId, Instant now);
    void refreshStatus(UUID organizationId, String status, Instant now);
}
