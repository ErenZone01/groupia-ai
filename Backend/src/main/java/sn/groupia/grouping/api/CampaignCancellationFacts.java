package sn.groupia.grouping.api;

import java.util.UUID;

/**
 * Internal scoped facts for already-authorized order workflows and durable event consumers. This
 * contract never changes a campaign. The locking read requires the caller's transaction.
 */
public interface CampaignCancellationFacts {
    record Fact(boolean cancelled, UUID actorId, String status) {}

    Fact cancellation(UUID organizationId, UUID campaignId);

    Fact lockForOrderWorkflow(UUID organizationId, UUID campaignId);
}
