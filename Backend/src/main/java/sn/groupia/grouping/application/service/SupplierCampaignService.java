package sn.groupia.grouping.application.service;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.grouping.api.SupplierCampaignLifecycle;
import sn.groupia.grouping.api.dto.CampaignLifecycleView;
import sn.groupia.grouping.domain.model.GroupingCampaign;
import sn.groupia.grouping.domain.repository.Campaigns;
import sn.groupia.organization.api.*;
import sn.groupia.shared.api.ApiException;
@Service
public class SupplierCampaignService implements SupplierCampaignLifecycle {
    private final Campaigns campaigns; private final OrganizationAccess access;
    SupplierCampaignService(Campaigns campaigns,OrganizationAccess access) {this.campaigns=campaigns;this.access=access;}
    @Transactional(propagation=Propagation.MANDATORY)
    public CampaignLifecycleView lock(UUID org,UUID id) {
        access.requirePermission(org,OrganizationPermission.MANAGE_GROUPINGS);
        return view(campaigns.lock(org,id).orElseThrow(SupplierCampaignService::missing));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public CampaignLifecycleView ordered(UUID org,UUID id,long version,Instant at) {
        access.requirePermission(org,OrganizationPermission.MANAGE_GROUPINGS);
        var c=campaigns.lock(org,id).orElseThrow(SupplierCampaignService::missing);
        c.requireVersion(version); c.supplierOrdered(at);
        return view(campaigns.save(c));
    }
    @Transactional(readOnly=true)
    public CampaignLifecycleView read(UUID org,UUID id) {
        access.requirePermission(org,OrganizationPermission.VIEW_ORDERS);
        return view(campaigns.find(org,id).orElseThrow(SupplierCampaignService::missing));
    }
    private CampaignLifecycleView view(GroupingCampaign c){return new CampaignLifecycleView(c.getId(),c.getOrganizationId(),c.getStatus().name(),c.getVersion());}
    private static ApiException missing(){return new ApiException(404,"GROUPING_CAMPAIGN_NOT_FOUND","Groupage introuvable.");}
}
