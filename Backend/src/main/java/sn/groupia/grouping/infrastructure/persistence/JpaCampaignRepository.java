package sn.groupia.grouping.infrastructure.persistence;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.groupia.grouping.domain.model.*;
interface JpaCampaignRepository extends JpaRepository<GroupingCampaign, UUID> {
    Optional<GroupingCampaign> findByOrganizationIdAndId(UUID organizationId, UUID id);
    Optional<GroupingCampaign> findByPublicIdentifier(UUID publicIdentifier);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from GroupingCampaign c where c.publicIdentifier = :publicIdentifier")
    Optional<GroupingCampaign> lockPublic(UUID publicIdentifier);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from GroupingCampaign c where c.organizationId = :organizationId and c.id = :campaignId")
    Optional<GroupingCampaign> lockScoped(UUID organizationId, UUID campaignId);
    Page<GroupingCampaign> findByOrganizationId(UUID organizationId, Pageable page);
    Page<GroupingCampaign> findByOrganizationIdAndStatus(UUID organizationId, GroupingCampaignStatus status, Pageable page);
}
