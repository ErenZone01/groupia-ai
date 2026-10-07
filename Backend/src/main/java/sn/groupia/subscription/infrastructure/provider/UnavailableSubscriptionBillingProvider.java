package sn.groupia.subscription.infrastructure.provider;

import sn.groupia.shared.api.ApiException;
import sn.groupia.subscription.application.service.SubscriptionBillingProvider;

public class UnavailableSubscriptionBillingProvider implements SubscriptionBillingProvider {
    @Override public boolean available() { return false; }
    @Override public ProviderSetup createCustomerAndSubscription(ProviderSetupRequest request) {
        throw unavailable();
    }
    @Override public void cancelSubscription(String providerCustomerId, String providerSubscriptionId) {
        throw unavailable();
    }
    private ApiException unavailable() {
        return new ApiException(503, "SUBSCRIPTION_BILLING_UNAVAILABLE",
                "Le paiement de l'abonnement sera bientôt disponible.");
    }
}
