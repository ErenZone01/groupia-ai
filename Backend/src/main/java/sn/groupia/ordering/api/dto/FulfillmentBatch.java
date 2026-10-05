package sn.groupia.ordering.api.dto;
import java.util.List;
import sn.groupia.grouping.api.dto.CampaignLifecycleView;
public record FulfillmentBatch(CampaignLifecycleView campaign,boolean canManageFulfillment,FulfillmentCounts counts,List<FulfillmentRow> orders){
    public record FulfillmentCounts(long total,long toPrepare,long paymentBlocked,long ready,long pickedUp,long customerCredits,long eligible){}
}
