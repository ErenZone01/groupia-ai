package sn.groupia.organization.api;

import java.util.UUID;
import sn.groupia.organization.api.dto.OrganizationSubscriptionFacts;

/** Minimal immutable organization facts used to provision a subscription trial. */
public interface OrganizationSubscriptionContext {
    OrganizationSubscriptionFacts subscriptionFacts(UUID organizationId);
}
