package sn.groupia.grouping.application.service;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.grouping.api.CampaignDistribution;
import sn.groupia.grouping.api.dto.CampaignLifecycleView;
import sn.groupia.grouping.domain.model.GroupingCampaign;
import sn.groupia.grouping.domain.repository.Campaigns;
import sn.groupia.organization.api.*;
import sn.groupia.shared.api.ApiException;
@Service @Transactional(propagation=Propagation.MANDATORY)
public class CampaignDistributionService implements CampaignDistribution {
    private final Campaigns campaigns;private final OrganizationAccess access;
    CampaignDistributionService(Campaigns campaigns,OrganizationAccess access){this.campaigns=campaigns;this.access=access;}
    public CampaignLifecycleView lockForDistribution(UUID org,UUID id){return view(lock(org,id));}
    public CampaignLifecycleView startDistribution(UUID org,UUID id,Instant now){
        var campaign=lock(org,id);campaign.distribute(now);return view(campaigns.save(campaign));
    }
    private GroupingCampaign lock(UUID org,UUID id){
        access.requirePermission(org,OrganizationPermission.MANAGE_FULFILLMENT);
        return campaigns.lock(org,id).orElseThrow(()->new ApiException(404,"GROUPING_CAMPAIGN_NOT_FOUND","Groupage introuvable."));
    }
    private CampaignLifecycleView view(GroupingCampaign c){return new CampaignLifecycleView(c.getId(),c.getOrganizationId(),c.getStatus().name(),c.getVersion());}
}
