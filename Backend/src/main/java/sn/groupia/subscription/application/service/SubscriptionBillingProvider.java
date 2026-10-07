package sn.groupia.subscription.application.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface SubscriptionBillingProvider {
    boolean available();
    ProviderSetup createCustomerAndSubscription(ProviderSetupRequest request);
    void cancelSubscription(String providerCustomerId, String providerSubscriptionId);

    record ProviderSetupRequest(
            UUID organizationId,
            UUID idempotencyKey,
            String reference,
            String name,
            String phoneE164,
            String email,
            String country,
            String locale,
            String planId,
            BigDecimal amount,
            String currency,
            String billingInterval,
            Instant firstPeriodStartsAt) {}

    record ProviderSetup(String provider, String customerId, String subscriptionId) {}
}
