package sn.groupia.grouping.api;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.grouping.api.dto.CampaignLifecycleView;
/** Atomic procurement coordinator contract. Mutations require an existing write transaction.
 * Call lock before obtaining the ordering projection. No persistence types escape. */
public interface SupplierCampaignLifecycle {
    CampaignLifecycleView lock(UUID organizationId, UUID campaignId);
    CampaignLifecycleView ordered(UUID organizationId, UUID campaignId, long expectedVersion, Instant orderedAt);
    CampaignLifecycleView read(UUID organizationId, UUID campaignId);
}
