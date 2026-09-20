package sn.groupia.grouping.application.service;

import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.catalog.api.CatalogVariants;
import sn.groupia.grouping.api.CheckoutAdmission;
import sn.groupia.grouping.api.dto.*;
import sn.groupia.grouping.domain.model.*;
import sn.groupia.grouping.domain.repository.*;
import sn.groupia.organization.api.OrganizationAvailability;
import sn.groupia.shared.api.ApiException;

@Service
@Transactional(propagation = Propagation.MANDATORY)
class GroupingCheckoutAdmission implements CheckoutAdmission {
    private final Campaigns campaigns;
    private final Offers offers;
    private final CatalogVariants catalog;
    private final OrganizationAvailability organizations;
    private final Clock clock;
    GroupingCheckoutAdmission(Campaigns campaigns, Offers offers, CatalogVariants catalog,
            OrganizationAvailability organizations, Clock clock) {
        this.campaigns = campaigns; this.offers = offers; this.catalog = catalog;
        this.organizations = organizations; this.clock = clock;
    }
    @Override public CheckoutCampaign lockCampaign(UUID publicIdentifier) {
        var campaign = campaigns.lockPublic(publicIdentifier)
                .orElseThrow(() -> new ApiException(404, "GROUPING_NOT_ORDERABLE", "Campagne indisponible."));
        // Resolve even closed campaigns so a successful checkout can be replayed without repricing.
        return new CheckoutCampaign(campaign.getOrganizationId(), campaign.getId(),
                organizations.countryCode(campaign.getOrganizationId()));
    }
    @Override public CheckoutQuote quote(CheckoutCampaign context, List<CheckoutSelection> selections) {
        var campaign = campaigns.find(context.organizationId(), context.campaignId()).orElseThrow();
        boolean active = organizations.isActive(context.organizationId());
        if (campaign.getStatus() == GroupingCampaignStatus.CLOSED
                || (campaign.getClosesAt() != null && !clock.instant().isBefore(campaign.getClosesAt()))) {
            throw new ApiException(409, "GROUPING_CLOSED", "La campagne est fermée aux commandes.");
        }
        if (!campaign.isOrderableAt(clock.instant(), active, !selections.isEmpty())) {
            throw new ApiException(409, "GROUPING_NOT_ORDERABLE", "La campagne ne peut pas recevoir cette commande.");
        }
        var selected = new ArrayList<GroupingOffer>();
        for (var line : selections) {
            var offer = offers.find(context.organizationId(), context.campaignId(), line.offerId())
                    .filter(GroupingOffer::isActive).orElseThrow(this::unavailable);
            if (!campaign.getCurrency().equals(offer.getCurrency())) { throw unavailable(); }
            if (line.quantity() <= 0) { throw new ApiException(400, "INVALID_ORDER_QUANTITY", "Quantité invalide."); }
            if (offer.getUnitPrice().compareTo(line.expectedUnitPrice()) != 0
                    || offer.getPriceRevision() != line.expectedPriceRevision()) {
                throw new ApiException(409, "GROUPING_OFFER_PRICE_CHANGED", "Le prix de cette offre a changé.",
                        Map.of("offerId", offer.getId(), "unitPrice", offer.getUnitPrice(),
                                "priceRevision", offer.getPriceRevision(), "version", offer.getVersion(), "currency", offer.getCurrency()));
            }
            selected.add(offer);
        }
        var snapshots = catalog.commercialSnapshots(context.organizationId(),
                selected.stream().map(GroupingOffer::getProductVariantId).collect(Collectors.toSet()));
        var quoted = new ArrayList<CheckoutOffer>();
        for (int i = 0; i < selected.size(); i++) {
            var offer = selected.get(i);
            var snapshot = snapshots.get(offer.getProductVariantId());
            if (snapshot == null || !snapshot.variant().usable()) { throw unavailable(); }
            var variant = snapshot.variant();
            quoted.add(new CheckoutOffer(offer.getId(), selections.get(i).quantity(), offer.getUnitPrice(),
                    offer.getPriceRevision(), offer.getVersion(), snapshot.productId(), offer.getProductVariantId(),
                    variant.productName(), variant.productDescription(), offer.getCommercialLabel(), variant.attributes(),
                    snapshot.sku(), variant.saleUnit(), variant.estimatedUnitWeightKg(), variant.estimatedUnitVolumeM3()));
        }
        return new CheckoutQuote(context.organizationId(), context.campaignId(), campaign.getCurrency(),
                campaign.getStatus().name(), true, campaign.getShippingTerms(), quoted);
    }
    private ApiException unavailable() {
        return new ApiException(409, "GROUPING_OFFER_NOT_AVAILABLE", "Offre absente, étrangère ou inutilisable.");
    }
}
