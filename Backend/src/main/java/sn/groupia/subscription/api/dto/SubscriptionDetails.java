package sn.groupia.subscription.api.dto;

import sn.groupia.subscription.api.SubscriptionAccessView;

public record SubscriptionDetails(
        SubscriptionAccessView access,
        boolean canManageSubscription,
        boolean billingProviderAvailable,
        boolean billingProviderSetupPending,
        boolean planConfigured,
        SubscriptionBillingContactView billingContact) {}
