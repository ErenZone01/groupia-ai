package sn.groupia.grouping.domain.repository;
import java.util.*;
import sn.groupia.grouping.domain.model.GroupingOffer;
public interface Offers {
    GroupingOffer save(GroupingOffer offer);
    Optional<GroupingOffer> find(UUID organizationId, UUID campaignId, UUID offerId);
    List<GroupingOffer> list(UUID organizationId, UUID campaignId);
    List<GroupingOffer> active(UUID organizationId, UUID campaignId);
}
