package sn.groupia.grouping.api;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.grouping.api.dto.CampaignLifecycleView;
/** Atomic distribution coordinator; requires MANAGE_FULFILLMENT and an existing transaction. */
public interface CampaignDistribution {
    CampaignLifecycleView lockForDistribution(UUID organizationId,UUID campaignId);
    CampaignLifecycleView startDistribution(UUID organizationId,UUID campaignId,Instant now);
}
