package sn.groupia.grouping.infrastructure.persistence;

import java.util.*;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Repository;
import sn.groupia.grouping.domain.model.*;
import sn.groupia.grouping.domain.repository.*;
import sn.groupia.shared.api.ApiException;

@Repository
class GroupingPersistence implements Campaigns, Offers, GroupingChanges {
    private final JpaCampaignRepository campaigns;
    private final JpaOfferRepository offers;
    private final EntityManager entityManager;
    GroupingPersistence(JpaCampaignRepository campaigns, JpaOfferRepository offers, EntityManager entityManager) {
        this.campaigns = campaigns; this.offers = offers; this.entityManager = entityManager;
    }

    @Override public List<GroupingCampaign> findAll(Set<UUID> ids){return campaigns.findAllById(ids);}
    @Override public GroupingCampaign save(GroupingCampaign campaign) {
        try { return campaigns.saveAndFlush(campaign); }
        catch (OptimisticLockingFailureException ex) {
            throw new ApiException(409, "GROUPING_CAMPAIGN_VERSION_CONFLICT", "La campagne a changé. Rechargez-la.");
        }
    }
    @Override public GroupingOffer save(GroupingOffer offer) {
        try { return offers.saveAndFlush(offer); }
        catch (OptimisticLockingFailureException ex) {
            throw new ApiException(409, "GROUPING_OFFER_PRICE_CONFLICT", "L'offre a changé. Rechargez-la.");
        } catch (DataIntegrityViolationException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException constraint
                        && "uk_grouping_offers_campaign_variant".equals(constraint.getConstraintName())) {
                    throw new ApiException(409, "GROUPING_OFFER_ALREADY_EXISTS", "Cette variante possède déjà une offre dans la campagne.");
                }
            }
            throw ex;
        }
    }
    @Override public Optional<GroupingCampaign> find(UUID organizationId, UUID campaignId) {
        return campaigns.findByOrganizationIdAndId(organizationId, campaignId);
    }
    @Override public Optional<GroupingCampaign> findPublic(UUID publicIdentifier) {
        return campaigns.findByPublicIdentifier(publicIdentifier);
    }
    @Override public Optional<GroupingCampaign> lock(UUID org, UUID id) { return campaigns.lockScoped(org,id); }
    @Override public Optional<GroupingCampaign> lockPublic(UUID publicIdentifier) { return campaigns.lockPublic(publicIdentifier); }
    @Override public CampaignResults list(UUID organizationId, GroupingCampaignStatus status, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        var result = status == null ? campaigns.findByOrganizationId(organizationId, pageable)
                : campaigns.findByOrganizationIdAndStatus(organizationId, status, pageable);
        return new CampaignResults(result.getContent(), result.getTotalElements(), result.getTotalPages());
    }
    @Override public Optional<GroupingOffer> find(UUID organizationId, UUID campaignId, UUID offerId) {
        return offers.findByOrganizationIdAndCampaignIdAndId(organizationId, campaignId, offerId);
    }
    @Override public List<GroupingOffer> list(UUID organizationId, UUID campaignId) {
        return offers.findByOrganizationIdAndCampaignIdOrderByCreatedAtAscIdAsc(organizationId, campaignId);
    }
    @Override public List<GroupingOffer> active(UUID organizationId, UUID campaignId) {
        return offers.findByOrganizationIdAndCampaignIdAndActiveTrueOrderByCreatedAtAscIdAsc(organizationId, campaignId);
    }
    @Override public void append(GroupingChange change) {
        entityManager.persist(change);
        entityManager.flush();
    }
}
