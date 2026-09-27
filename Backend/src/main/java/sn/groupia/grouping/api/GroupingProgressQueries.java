package sn.groupia.grouping.api;
import java.util.UUID;
import sn.groupia.grouping.api.dto.GroupingProgress;
/** Internal query: caller must first authorize the order holder and derive both IDs from that order. */
public interface GroupingProgressQueries {
    GroupingProgress progress(UUID organizationId, UUID campaignId);
}
