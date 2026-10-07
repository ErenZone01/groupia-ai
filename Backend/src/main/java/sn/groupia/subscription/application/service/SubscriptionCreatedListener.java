package sn.groupia.subscription.application.service;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import sn.groupia.organization.api.event.OrganizationCreated;

@Component
class SubscriptionCreatedListener {
    private final SubscriptionProvisioning provisioning;
    SubscriptionCreatedListener(SubscriptionProvisioning provisioning) { this.provisioning = provisioning; }

    @ApplicationModuleListener
    void on(OrganizationCreated event) {
        provisioning.ensure(event.organizationId());
    }
}
