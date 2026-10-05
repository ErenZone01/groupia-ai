package sn.groupia.grouping.domain.model;

import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import jakarta.persistence.*;
import sn.groupia.shared.api.ApiException;
import sn.groupia.shipping.api.dto.ShippingTerms;

@Entity
@Table(name = "grouping_campaigns")
public class GroupingCampaign {
    @Id private UUID id;
    @Column(nullable = false, updatable = false) private UUID organizationId;
    @Column(nullable = false, updatable = false, unique = true) private UUID publicIdentifier;
    @Column(nullable = false, length = 160) private String slug;
    @Column(nullable = false, length = 160) private String title;
    @Column(length = 5000) private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private GroupingCampaignStatus status;
    @Column(nullable = false, length = 3) private String currency;
    private Instant closesAt;
    @Embedded private CampaignTerms terms;
    private Instant openedAt;
    private Instant closedAt;
    private Instant cancelledAt;
    @Column(length = 500) private String cancellationReason;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    @Version private Long version;

    protected GroupingCampaign() {}

    public static GroupingCampaign create(UUID organizationId, String title, String description,
            Instant closesAt, ShippingTerms terms, Instant now) {
        var campaign = new GroupingCampaign();
        campaign.id = UUID.randomUUID();
        campaign.publicIdentifier = UUID.randomUUID();
        campaign.organizationId = Objects.requireNonNull(organizationId);
        campaign.information(title, description);
        campaign.status = GroupingCampaignStatus.DRAFT;
        campaign.currency = "XOF";
        campaign.closesAt = closesAt;
        campaign.terms = CampaignTerms.from(terms);
        campaign.createdAt = campaign.updatedAt = now.truncatedTo(ChronoUnit.MICROS);
        return campaign;
    }

    public void requireVersion(long expected) {
        if (version == null || version != expected) {
            throw new ApiException(409, "GROUPING_CAMPAIGN_VERSION_CONFLICT", "La campagne a changé. Rechargez-la.");
        }
    }

    public void update(String title, String description, Instant closesAt, ShippingTerms terms, Instant now) {
        requireEditable();
        if (status == GroupingCampaignStatus.OPEN) {
            if (terms != null && !terms.equals(getShippingTerms())) {
                throw new ApiException(409, "SHIPPING_TERMS_FROZEN", "Les conditions de transport sont figées après ouverture.");
            }
            if (!Objects.equals(this.closesAt, closesAt) && closesAt != null && !closesAt.isAfter(now)) {
                throw new ApiException(400, "INVALID_GROUPING_DEADLINE", "La nouvelle échéance doit être future.");
            }
        } else {
            this.terms = CampaignTerms.from(terms);
        }
        information(title, description);
        this.closesAt = closesAt;
        touch(now);
    }

    public void open(long activeOffers, Instant now) {
        if (status != GroupingCampaignStatus.DRAFT) { throw invalidTransition(); }
        if (activeOffers < 1) {
            throw new ApiException(409, "GROUPING_HAS_NO_ACTIVE_OFFERS", "Au moins une offre active est nécessaire.");
        }
        if (terms == null || !"XOF".equals(currency) || (closesAt != null && !closesAt.isAfter(now))) {
            throw new ApiException(409, "GROUPING_CAMPAIGN_NOT_OPENABLE", "Transport ou échéance incompatible avec l'ouverture.");
        }
        status = GroupingCampaignStatus.OPEN;
        openedAt = now;
        touch(now);
    }

    public void close(Instant now) {
        if (status == GroupingCampaignStatus.CLOSED) {
            throw new ApiException(409, "GROUPING_ALREADY_CLOSED", "La campagne est déjà fermée.");
        }
        if (status != GroupingCampaignStatus.OPEN) { throw invalidTransition(); }
        status = GroupingCampaignStatus.CLOSED;
        closedAt = now;
        touch(now);
    }

    public void supplierOrdered(Instant now) {
        if(status != GroupingCampaignStatus.CLOSED) throw invalidTransition();
        status = GroupingCampaignStatus.SUPPLIER_ORDERED;
        touch(now);
    }

    public void enrichLogistics(Instant now) {
        if(status!=GroupingCampaignStatus.SUPPLIER_ORDERED && status!=GroupingCampaignStatus.IN_TRANSIT
                && status!=GroupingCampaignStatus.ARRIVED)throw invalidTransition();
        touch(now);
    }

    public void ship(Instant now) {
        if(status != GroupingCampaignStatus.SUPPLIER_ORDERED) throw invalidTransition();
        status=GroupingCampaignStatus.IN_TRANSIT;
        touch(now);
    }
    public void distribute(Instant now) {
        if(status==GroupingCampaignStatus.DISTRIBUTING)return;
        if(status!=GroupingCampaignStatus.ARRIVED)throw invalidTransition();
        status=GroupingCampaignStatus.DISTRIBUTING;touch(now);
    }
    public void arrive(Instant now) {
        if(status != GroupingCampaignStatus.IN_TRANSIT) throw invalidTransition();
        status=GroupingCampaignStatus.ARRIVED;
        touch(now);
    }

    public void cancel(String reason, Instant now) {
        if (status != GroupingCampaignStatus.DRAFT && status != GroupingCampaignStatus.OPEN
                && status != GroupingCampaignStatus.CLOSED) { throw invalidTransition(); }
        if (reason == null || reason.isBlank() || reason.strip().length() > 500 || reason.indexOf(0) >= 0) {
            throw new ApiException(400, "INVALID_CANCELLATION_REASON", "Un motif d'annulation est requis.");
        }
        status = GroupingCampaignStatus.CANCELLED;
        cancelledAt = now;
        cancellationReason = reason.strip();
        touch(now);
    }

    public void coordinateOfferChange(Instant now) {
        requireEditable();
        touch(now);
    }

    public void requireDeactivationAllowed(long activeOffers, boolean currentlyActive) {
        requireEditable();
        if (status == GroupingCampaignStatus.OPEN && currentlyActive && activeOffers <= 1) {
            throw new ApiException(409, "GROUPING_HAS_NO_ACTIVE_OFFERS", "Fermez ou annulez la campagne avant de retirer sa dernière offre.");
        }
    }

    public void requireEditable() {
        if (status == GroupingCampaignStatus.CANCELLED) {
            throw new ApiException(409, "GROUPING_CANCELLED", "La campagne est annulée.");
        }
        if (status != GroupingCampaignStatus.DRAFT && status != GroupingCampaignStatus.OPEN) { throw invalidTransition(); }
    }

    /** The single admission predicate. Future checkout must invoke it again within its coordinated transaction. */
    public boolean isOrderableAt(Instant now, boolean organizationActive, boolean hasUsableOffers) {
        return organizationActive && hasUsableOffers && status == GroupingCampaignStatus.OPEN
                && (closesAt == null || now.isBefore(closesAt));
    }

    private void information(String title, String description) {
        if (title == null || title.isBlank() || title.strip().length() > 160
                || title.codePoints().anyMatch(Character::isISOControl)
                || (description != null && (description.length() > 5000 || description.indexOf(0) >= 0))) {
            throw new ApiException(400, "INVALID_GROUPING_INFORMATION", "Titre ou description invalide.");
        }
        this.title = title.strip();
        this.description = description == null || description.isBlank() ? null : description.strip();
        String generated = Normalizer.normalize(this.title, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        slug = generated.isEmpty() ? "groupage" : generated;
    }

    private void touch(Instant now) {
        Instant candidate = now.truncatedTo(ChronoUnit.MICROS);
        updatedAt = candidate.isAfter(updatedAt) ? candidate : updatedAt.plus(1, ChronoUnit.MICROS);
    }

    private ApiException invalidTransition() {
        return new ApiException(409, "INVALID_GROUPING_TRANSITION", "Transition interdite depuis cet état.");
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getPublicIdentifier() { return publicIdentifier; }
    public String getSlug() { return slug; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public GroupingCampaignStatus getStatus() { return status; }
    public String getCurrency() { return currency; }
    public Instant getClosesAt() { return closesAt; }
    public ShippingTerms getShippingTerms() { return terms == null ? null : terms.view(); }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getClosedAt() { return closedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public String getCancellationReason() { return cancellationReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
