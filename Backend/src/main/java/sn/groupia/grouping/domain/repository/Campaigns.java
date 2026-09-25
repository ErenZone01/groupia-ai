package sn.groupia.grouping.domain.repository;
import java.util.*;
import sn.groupia.grouping.domain.model.*;
public interface Campaigns {
    GroupingCampaign save(GroupingCampaign campaign);
    Optional<GroupingCampaign> find(UUID organizationId, UUID campaignId);
    Optional<GroupingCampaign> findPublic(UUID publicIdentifier);
    Optional<GroupingCampaign> lockPublic(UUID publicIdentifier);
    CampaignResults list(UUID organizationId, GroupingCampaignStatus status, int page, int size);
    record CampaignResults(List<GroupingCampaign> items, long totalElements, int totalPages) {}
}
