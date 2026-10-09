package sn.groupia.grouping.application.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.catalog.api.CatalogVariants;
import sn.groupia.catalog.api.dto.CatalogVariantView;
import sn.groupia.grouping.api.GroupingQueries;
import sn.groupia.grouping.api.dto.*;
import sn.groupia.grouping.domain.model.*;
import sn.groupia.grouping.domain.repository.*;
import sn.groupia.organization.api.*;
import sn.groupia.shared.api.ApiException;
import sn.groupia.shipping.api.dto.ShippingTerms;
import sn.groupia.subscription.api.SubscriptionEntitlements;

@Service
@Transactional(readOnly = true)
public class GroupingService implements GroupingQueries {
    private final Campaigns campaigns;
    private final Offers offers;
    private final GroupingChanges changes;
    private final CatalogVariants catalog;
    private final OrganizationAccess access;
    private final OrganizationAvailability organizations;
    private final SubscriptionEntitlements subscriptionEntitlements;
    private final Clock clock;

    public GroupingService(Campaigns campaigns, Offers offers, GroupingChanges changes, CatalogVariants catalog,
            OrganizationAccess access, OrganizationAvailability organizations,
            SubscriptionEntitlements subscriptionEntitlements, Clock clock) {
        this.campaigns = campaigns; this.offers = offers; this.changes = changes; this.catalog = catalog;
        this.access = access; this.organizations = organizations; this.subscriptionEntitlements = subscriptionEntitlements;
        this.clock = clock;
    }

    @Transactional
    public CampaignView create(UUID organizationId, String title, String description, Instant closesAt, ShippingTerms terms) {
        access.requirePermission(organizationId, OrganizationPermission.MANAGE_GROUPINGS);
        subscriptionEntitlements.requireNewBusinessAllowed(organizationId);
        return view(campaigns.save(GroupingCampaign.create(organizationId, title, description, closesAt, terms, clock.instant())));
    }

    public CampaignPage list(UUID organizationId, String status, int page, int size) {
        access.requirePermission(organizationId, OrganizationPermission.VIEW_GROUPINGS);
        if (page < 0 || page > 1000000 || size < 1 || size > 100) {
            throw new ApiException(400, "INVALID_REQUEST", "Pagination invalide.");
        }
        GroupingCampaignStatus filter = null;
        if (status != null) {
            try { filter = GroupingCampaignStatus.valueOf(status); }
            catch (IllegalArgumentException ex) { throw new ApiException(400, "INVALID_REQUEST", "Statut invalide."); }
        }
        var result = campaigns.list(organizationId, filter, page, size);
        return new CampaignPage(result.items().stream().map(campaign -> new CampaignSummary(campaign.getId(),
                campaign.getPublicIdentifier(), campaign.getSlug(), campaign.getTitle(), campaign.getStatus().name(),
                campaign.getClosesAt(), campaign.getCreatedAt(), campaign.getVersion())).toList(),
                page, size, result.totalElements(), result.totalPages());
    }

    @Override
    public void requireCampaignBelongsTo(UUID organizationId, UUID campaignId) {
        find(organizationId, campaignId);
    }

    public CampaignView get(UUID organizationId, UUID campaignId) {
        access.requirePermission(organizationId, OrganizationPermission.VIEW_GROUPINGS);
        return view(find(organizationId, campaignId));
    }

    @Transactional
    public CampaignView update(UUID organizationId, UUID campaignId, long version, String title,
            String description, Instant closesAt, ShippingTerms terms) {
        UUID actor = access.requireActorPermission(organizationId, OrganizationPermission.MANAGE_GROUPINGS);
        var campaign = find(organizationId, campaignId);
        campaign.requireVersion(version);
        Instant oldDeadline = campaign.getClosesAt();
        Instant now = clock.instant();
        campaign.update(title, description, closesAt, terms, now);
        campaigns.save(campaign);
        if (!Objects.equals(oldDeadline, closesAt)) {
            changes.append(GroupingChange.deadline(campaign, actor, oldDeadline, now));
        }
        return view(campaign);
    }

    @Transactional
    public CampaignView open(UUID organizationId, UUID campaignId, long version) {
        return transition(organizationId, campaignId, version, campaign -> {
            subscriptionEntitlements.requireNewBusinessAllowed(organizationId);
            var active = offers.active(organizationId, campaignId);
            campaign.open(active.size(), clock.instant());
            var variants = catalog.findByIds(organizationId,
                    active.stream().map(GroupingOffer::getProductVariantId).collect(Collectors.toSet()));
            active.forEach(offer -> requireUsable(variants.get(offer.getProductVariantId()), campaign.getShippingTerms()));
        });
    }

    @Transactional
    public CampaignView close(UUID organizationId, UUID campaignId, long version) {
        return transition(organizationId, campaignId, version, campaign -> campaign.close(clock.instant()));
    }

    @Transactional
    public CampaignView cancel(UUID organizationId, UUID campaignId, long version, String reason) {
        return transition(organizationId, campaignId, version, campaign -> campaign.cancel(reason, clock.instant()));
    }

    @Transactional
    public CampaignView addOffer(UUID organizationId, UUID campaignId, long campaignVersion,
            UUID variantId, String label, BigDecimal price) {
        access.requirePermission(organizationId, OrganizationPermission.MANAGE_GROUPINGS);
        var campaign = find(organizationId, campaignId);
        campaign.requireVersion(campaignVersion);
        campaign.requireEditable();
        requireUsable(catalog.findByIds(organizationId, Set.of(variantId)).get(variantId),
                campaign.getStatus() == GroupingCampaignStatus.OPEN ? campaign.getShippingTerms() : null);
        var offer = GroupingOffer.create(organizationId, campaignId, variantId, label, price, clock.instant());
        campaign.coordinateOfferChange(clock.instant());
        campaigns.save(campaign);
        offers.save(offer);
        return view(campaign);
    }

    @Transactional
    public CampaignView updateOffer(UUID organizationId, UUID campaignId, UUID offerId,
            long campaignVersion, long offerVersion, String label, BigDecimal price) {
        UUID actor = access.requireActorPermission(organizationId, OrganizationPermission.MANAGE_GROUPINGS);
        var campaign = find(organizationId, campaignId);
        campaign.requireVersion(campaignVersion);
        campaign.requireEditable();
        var offer = findOffer(organizationId, campaignId, offerId);
        offer.requireVersion(offerVersion);
        BigDecimal oldPrice = offer.getUnitPrice();
        Instant now = clock.instant();
        boolean priceChanged = offer.update(label, price, now);
        campaign.coordinateOfferChange(now);
        campaigns.save(campaign);
        offers.save(offer);
        if (priceChanged) { changes.append(GroupingChange.price(campaign, offer, actor, oldPrice, now)); }
        return view(campaign);
    }

    @Transactional
    public CampaignView deactivateOffer(UUID organizationId, UUID campaignId, UUID offerId,
            long campaignVersion, long offerVersion) {
        return changeActivation(organizationId, campaignId, offerId, campaignVersion, offerVersion, false);
    }

    @Transactional
    public CampaignView activateOffer(UUID organizationId, UUID campaignId, UUID offerId,
            long campaignVersion, long offerVersion) {
        return changeActivation(organizationId, campaignId, offerId, campaignVersion, offerVersion, true);
    }

    private CampaignView changeActivation(UUID organizationId, UUID campaignId, UUID offerId,
            long campaignVersion, long offerVersion, boolean activate) {
        access.requirePermission(organizationId, OrganizationPermission.MANAGE_GROUPINGS);
        var campaign = find(organizationId, campaignId);
        campaign.requireVersion(campaignVersion);
        campaign.requireEditable();
        var offer = findOffer(organizationId, campaignId, offerId);
        offer.requireVersion(offerVersion);
        if (activate) {
            requireUsable(catalog.findByIds(organizationId, Set.of(offer.getProductVariantId())).get(offer.getProductVariantId()),
                    campaign.getStatus() == GroupingCampaignStatus.OPEN ? campaign.getShippingTerms() : null);
        } else {
            campaign.requireDeactivationAllowed(offers.active(organizationId, campaignId).size(), offer.isActive());
        }
        Instant now = clock.instant();
        campaign.coordinateOfferChange(now);
        // Flush the campaign decision before mutating a separate offer root.
        campaigns.save(campaign);
        if (activate) { offer.activate(now); } else { offer.deactivate(now); }
        offers.save(offer);
        return view(campaign);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PublicGroupingView getPublicGrouping(String identifier) {
        UUID publicId;
        try { publicId = UUID.fromString(identifier); }
        catch (IllegalArgumentException ex) { throw notFound(); }
        var campaign = campaigns.findPublic(publicId).orElseThrow(this::notFound);
        boolean activeOrganization = organizations.isActive(campaign.getOrganizationId());
        if (campaign.getOpenedAt() == null || !activeOrganization) { throw notFound(); }
        var active = offers.active(campaign.getOrganizationId(), campaign.getId());
        var variants = catalog.findByIds(campaign.getOrganizationId(),
                active.stream().map(GroupingOffer::getProductVariantId).collect(Collectors.toSet()));
        var visible = active.stream().filter(offer -> {
            var variant = variants.get(offer.getProductVariantId());
            return variant != null && variant.usable() && campaign.getShippingTerms().canEstimate(
                    variant.estimatedUnitWeightKg(), variant.estimatedUnitVolumeM3());
        }).map(offer -> new PublicOfferView(offer.getId(), offer.getProductVariantId(), offer.getCommercialLabel(),
                offer.getUnitPrice(), offer.getCurrency(), true, offer.getVersion(), offer.getPriceRevision(),
                variants.get(offer.getProductVariantId()))).toList();
        return new PublicGroupingView(campaign.getPublicIdentifier(), campaign.getSlug(), campaign.getTitle(),
                campaign.getDescription(), campaign.getStatus().name(), campaign.getCurrency(),
                campaign.isOrderableAt(clock.instant(), activeOrganization, !visible.isEmpty()),
                campaign.getClosesAt(), campaign.getVersion(), campaign.getShippingTerms(), visible);
    }

    private void requireUsable(CatalogVariantView variant, ShippingTerms terms) {
        if (variant == null || !variant.usable()) {
            throw new ApiException(409, "GROUPING_OFFER_NOT_AVAILABLE", "Variante absente, étrangère ou inutilisable.");
        }
        if (terms != null && !terms.canEstimate(variant.estimatedUnitWeightKg(), variant.estimatedUnitVolumeM3())) {
            throw new ApiException(409, "INVALID_SHIPPING_TERMS", "Une estimation catalogue est requise pour ce mode de perception.");
        }
    }

    private CampaignView transition(UUID organizationId, UUID campaignId, long version, Consumer<GroupingCampaign> transition) {
        access.requirePermission(organizationId, OrganizationPermission.MANAGE_GROUPINGS);
        var campaign = find(organizationId, campaignId);
        campaign.requireVersion(version);
        transition.accept(campaign);
        campaigns.save(campaign);
        return view(campaign);
    }

    private GroupingCampaign find(UUID organizationId, UUID campaignId) {
        return campaigns.find(organizationId, campaignId).orElseThrow(this::notFound);
    }
    private GroupingOffer findOffer(UUID organizationId, UUID campaignId, UUID offerId) {
        return offers.find(organizationId, campaignId, offerId)
                .orElseThrow(() -> new ApiException(404, "GROUPING_OFFER_NOT_FOUND", "Offre introuvable dans cette campagne."));
    }
    private ApiException notFound() {
        return new ApiException(404, "GROUPING_CAMPAIGN_NOT_FOUND", "Campagne introuvable.");
    }
    private CampaignView view(GroupingCampaign campaign) {
        return new CampaignView(campaign.getId(), campaign.getOrganizationId(), campaign.getPublicIdentifier(), campaign.getSlug(),
                campaign.getTitle(), campaign.getDescription(), campaign.getStatus().name(), campaign.getCurrency(),
                campaign.getClosesAt(), campaign.getShippingTerms(), campaign.getOpenedAt(), campaign.getClosedAt(),
                campaign.getCancelledAt(), campaign.getCancellationReason(), campaign.getCreatedAt(), campaign.getUpdatedAt(),
                campaign.getVersion(), offers.list(campaign.getOrganizationId(), campaign.getId()).stream()
                        .map(offer -> new OfferView(offer.getId(), offer.getProductVariantId(), offer.getCommercialLabel(),
                                offer.getUnitPrice(), offer.getCurrency(), offer.isActive(), offer.getVersion(),
                                offer.getPriceRevision(), offer.getCreatedAt(), offer.getUpdatedAt())).toList());
    }
}
