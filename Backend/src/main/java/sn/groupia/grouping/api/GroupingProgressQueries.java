package sn.groupia.grouping.api;
import java.util.UUID;
import sn.groupia.grouping.api.dto.GroupingProgress;
/** Internal query: caller must first authorize the order holder and derive both IDs from that order. */
public interface GroupingProgressQueries {
    java.util.Map<UUID,sn.groupia.grouping.api.dto.PublicCampaignProgress> progressForOrders(java.util.Set<UUID> campaignIds);
    GroupingProgress progress(UUID organizationId, UUID campaignId);
}
