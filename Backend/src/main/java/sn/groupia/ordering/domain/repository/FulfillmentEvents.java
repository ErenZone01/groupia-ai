package sn.groupia.ordering.domain.repository;
import java.util.*;
import sn.groupia.ordering.domain.model.FulfillmentEvent;
public interface FulfillmentEvents {
    void append(FulfillmentEvent event);
    List<FulfillmentEvent> forCampaign(UUID organizationId,UUID campaignId);
}
