package sn.groupia.grouping.application.service;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.grouping.api.MerchantCampaignFacts;
import sn.groupia.grouping.domain.repository.Campaigns;
import sn.groupia.grouping.domain.model.GroupingCampaign;
import sn.groupia.shared.api.ApiException;
@Service @Transactional(readOnly=true)
class MerchantCampaignFactsService implements MerchantCampaignFacts {
    private final Campaigns campaigns;
    MerchantCampaignFactsService(Campaigns c) { campaigns=c; }
    public Fact read(UUID org,UUID id) { return view(campaigns.find(org,id).orElseThrow(MerchantCampaignFactsService::missing)); }
    @Transactional(propagation=Propagation.MANDATORY)
    public Fact lock(UUID org,UUID id) { return view(campaigns.lock(org,id).orElseThrow(MerchantCampaignFactsService::missing)); }
    private Fact view(GroupingCampaign c) { return new Fact(c.getId(),c.getTitle(),c.getStatus().name()); }
    private static ApiException missing() { return new ApiException(404,"GROUPING_CAMPAIGN_NOT_FOUND","Groupage introuvable."); }
}
