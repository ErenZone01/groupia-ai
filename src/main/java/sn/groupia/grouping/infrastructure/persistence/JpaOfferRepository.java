package sn.groupia.grouping.infrastructure.persistence;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.groupia.grouping.domain.model.GroupingOffer;
interface JpaOfferRepository extends JpaRepository<GroupingOffer, UUID> {
    Optional<GroupingOffer> findByOrganizationIdAndCampaignIdAndId(UUID organizationId, UUID campaignId, UUID id);
    List<GroupingOffer> findByOrganizationIdAndCampaignIdOrderByCreatedAtAscIdAsc(UUID organizationId, UUID campaignId);
    List<GroupingOffer> findByOrganizationIdAndCampaignIdAndActiveTrueOrderByCreatedAtAscIdAsc(UUID organizationId, UUID campaignId);
}
