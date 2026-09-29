package sn.groupia.grouping.domain.repository;
import java.util.*;
import sn.groupia.grouping.domain.model.*;
public interface Campaigns {
    java.util.List<sn.groupia.grouping.domain.model.GroupingCampaign> findAll(java.util.Set<UUID> ids);
    GroupingCampaign save(GroupingCampaign campaign);
    Optional<GroupingCampaign> find(UUID organizationId, UUID campaignId);
    Optional<GroupingCampaign> findPublic(UUID publicIdentifier);
    Optional<GroupingCampaign> lock(UUID organizationId, UUID campaignId);
    Optional<GroupingCampaign> lockPublic(UUID publicIdentifier);
    CampaignResults list(UUID organizationId, GroupingCampaignStatus status, int page, int size);
    record CampaignResults(List<GroupingCampaign> items, long totalElements, int totalPages) {}
}
