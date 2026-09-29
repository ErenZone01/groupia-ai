package sn.groupia.grouping.application.service;
import java.time.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.grouping.api.GroupingProgressQueries;
import sn.groupia.grouping.api.dto.*;
import sn.groupia.grouping.domain.model.*;
import sn.groupia.grouping.domain.repository.*;
import sn.groupia.organization.api.*;
import sn.groupia.shipping.api.Shipments;
import sn.groupia.shared.api.ApiException;

@Service
public class CampaignLogistics implements GroupingProgressQueries {
    private final Campaigns campaigns;
    private final GroupingChanges changes;
    private final Shipments shipments;
    private final OrganizationAccess access;
    private final Clock clock;
    CampaignLogistics(Campaigns campaigns,GroupingChanges changes,Shipments shipments,OrganizationAccess access,Clock clock) {
        this.campaigns=campaigns;this.changes=changes;this.shipments=shipments;this.access=access;this.clock=clock;
    }
    @Transactional
    public CampaignShipmentView ship(UUID org,UUID id,long version,Instant departure,Instant expected,String reference,String note) {
        UUID actor=access.requireActorPermission(org,OrganizationPermission.MANAGE_GROUPINGS);
        var campaign=lock(org,id);campaign.requireVersion(version);
        Instant now=clock.instant();
        campaign.ship(now);
        var shipment=shipments.depart(org,id,campaign.getShippingTerms().transportMode(),departure,expected,reference,note,now);
        campaigns.save(campaign);
        changes.append(GroupingChange.logistics(campaign,actor,GroupingCampaignStatus.SUPPLIER_ORDERED,now));
        return new CampaignShipmentView(lifecycle(campaign),shipment);
    }
    @Transactional
    public CampaignShipmentView arrive(UUID org,UUID id,long version,Instant arrival) {
        UUID actor=access.requireActorPermission(org,OrganizationPermission.MANAGE_GROUPINGS);
        var campaign=lock(org,id);campaign.requireVersion(version);
        Instant now=clock.instant();
        campaign.arrive(now);
        var shipment=shipments.arrive(org,id,arrival,now);
        campaigns.save(campaign);
        changes.append(GroupingChange.logistics(campaign,actor,GroupingCampaignStatus.IN_TRANSIT,now));
        return new CampaignShipmentView(lifecycle(campaign),shipment);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public CampaignShipmentView read(UUID org,UUID id) {
        access.requirePermission(org,OrganizationPermission.VIEW_GROUPINGS);
        var campaign=campaigns.find(org,id).orElseThrow(CampaignLogistics::missing);
        return new CampaignShipmentView(lifecycle(campaign),shipments.find(org,id).orElse(null));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public GroupingProgress progress(UUID org,UUID id) {
        var campaign=campaigns.find(org,id).orElseThrow(CampaignLogistics::missing);
        var shipment=shipments.find(org,id).orElse(null);
        return new GroupingProgress(campaign.getStatus().name(),shipment==null?null:shipment.departedAt(),
            shipment==null?null:shipment.expectedArrivalAt(),shipment==null?null:shipment.arrivedAt());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public java.util.Map<UUID,PublicCampaignProgress> progressForOrders(java.util.Set<UUID> ids) {
        if(ids.size()>50)throw new IllegalArgumentException("Batch too large");
        if(ids.isEmpty())return java.util.Map.of();
        var shipping=shipments.findAll(ids);
        var result=new java.util.HashMap<UUID,PublicCampaignProgress>();
        for(var c:campaigns.findAll(ids)) {
            var s=shipping.get(c.getId());
            result.put(c.getId(),new PublicCampaignProgress(c.getTitle(),new GroupingProgress(c.getStatus().name(),
                s==null?null:s.departedAt(),s==null?null:s.expectedArrivalAt(),s==null?null:s.arrivedAt())));
        }
        return java.util.Map.copyOf(result);
    }
    private GroupingCampaign lock(UUID org,UUID id){return campaigns.lock(org,id).orElseThrow(CampaignLogistics::missing);}
    private CampaignLifecycleView lifecycle(GroupingCampaign c){return new CampaignLifecycleView(c.getId(),c.getOrganizationId(),c.getStatus().name(),c.getVersion());}
    private static ApiException missing(){return new ApiException(404,"GROUPING_CAMPAIGN_NOT_FOUND","Groupage introuvable.");}
}
