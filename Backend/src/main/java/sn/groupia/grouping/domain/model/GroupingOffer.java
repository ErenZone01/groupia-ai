package sn.groupia.grouping.domain.model;

import java.math.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import jakarta.persistence.*;
import sn.groupia.shared.api.ApiException;

@Entity
@Table(name = "grouping_offers")
public class GroupingOffer {
    @Id private UUID id;
    @Column(nullable = false, updatable = false) private UUID organizationId;
    @Column(nullable = false, updatable = false) private UUID campaignId;
    @Column(nullable = false, updatable = false) private UUID productVariantId;
    @Column(length = 160) private String commercialLabel;
    @Column(nullable = false, columnDefinition = "numeric") private BigDecimal unitPrice;
    @Column(nullable = false, length = 3, updatable = false) private String currency;
    @Column(nullable = false) private long priceRevision;
    @Column(nullable = false) private boolean active;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    @Version private Long version;

    protected GroupingOffer() {}

    public static GroupingOffer create(UUID organizationId, UUID campaignId, UUID productVariantId,
            String commercialLabel, BigDecimal unitPrice, Instant now) {
        var offer = new GroupingOffer();
        offer.id = UUID.randomUUID();
        offer.organizationId = Objects.requireNonNull(organizationId);
        offer.campaignId = Objects.requireNonNull(campaignId);
        offer.productVariantId = Objects.requireNonNull(productVariantId);
        offer.commercialLabel = label(commercialLabel);
        offer.unitPrice = price(unitPrice);
        offer.currency = "XOF";
        offer.active = true;
        offer.createdAt = offer.updatedAt = now.truncatedTo(ChronoUnit.MICROS);
        return offer;
    }

    public void requireVersion(long expected) {
        if (version == null || version != expected) {
            throw new ApiException(409, "GROUPING_OFFER_PRICE_CONFLICT", "L'offre a changé. Rechargez son prix et sa version.");
        }
    }

    public boolean update(String label, BigDecimal price, Instant now) {
        BigDecimal normalized = price(price);
        boolean changed = unitPrice.compareTo(normalized) != 0;
        commercialLabel = label(label);
        unitPrice = normalized;
        if (changed) { priceRevision++; }
        touch(now);
        return changed;
    }

    public void deactivate(Instant now) { active = false; touch(now); }
    public void activate(Instant now) { active = true; touch(now); }

    private static BigDecimal price(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > 0
                || value.compareTo(new BigDecimal("1000000000000000000")) >= 0) {
            throw new ApiException(400, "INVALID_GROUPING_OFFER_PRICE", "Le prix XOF doit être un entier positif ou nul, de 18 chiffres maximum.");
        }
        return value.setScale(0, RoundingMode.UNNECESSARY);
    }
    private static String label(String label) {
        if (label != null && (label.length() > 160 || label.indexOf(0) >= 0)) {
            throw new ApiException(400, "INVALID_GROUPING_OFFER_LABEL", "Libellé commercial invalide.");
        }
        return label == null || label.isBlank() ? null : label.strip();
    }
    private void touch(Instant now) {
        Instant candidate = now.truncatedTo(ChronoUnit.MICROS);
        updatedAt = candidate.isAfter(updatedAt) ? candidate : updatedAt.plus(1, ChronoUnit.MICROS);
    }
    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getCampaignId() { return campaignId; }
    public UUID getProductVariantId() { return productVariantId; }
    public String getCommercialLabel() { return commercialLabel; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public String getCurrency() { return currency; }
    public long getPriceRevision() { return priceRevision; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
