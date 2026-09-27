package sn.groupia.grouping.api;
import sn.groupia.grouping.api.dto.PublicGroupingView;
public interface GroupingQueries {
    PublicGroupingView getPublicGrouping(String publicIdentifier);
    /** Ownership check for callers that authorize their own operation; never exposes campaign internals. */
    void requireCampaignBelongsTo(java.util.UUID organizationId, java.util.UUID campaignId);
}
