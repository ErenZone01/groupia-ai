package sn.groupia.grouping.domain.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Durable local history. No cross-module event consumer or public audit endpoint. */
@Entity
@Immutable
@Table(name = "grouping_changes")
public class GroupingChange {
    @Id private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false, updatable = false)
    private UUID campaignId;

    @Column(updatable = false)
    private UUID offerId;

    @Column(nullable = false, updatable = false)
    private UUID actorId;

    @Column(nullable = false, length = 32, updatable = false)
    private String kind;

    @Column(nullable = false, updatable = false)
    private long campaignVersion;

    @Column(updatable = false)
    private Long priceRevision;

    @Column(columnDefinition = "numeric", updatable = false)
    private BigDecimal oldPrice;

    @Column(columnDefinition = "numeric", updatable = false)
    private BigDecimal newPrice;

    @Column(length = 3, updatable = false)
    private String currency;

    @Column(updatable = false)
    private Instant oldClosesAt;

    @Column(updatable = false)
    private Instant newClosesAt;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(length = 32, updatable = false)
    private String fromStatus;

    @Column(length = 32, updatable = false)
    private String toStatus;

    protected GroupingChange() {}

    private static GroupingChange base(
            GroupingCampaign campaign, UUID actorId, Instant now, String kind) {
        var change = new GroupingChange();
        change.id = UUID.randomUUID();
        change.organizationId = campaign.getOrganizationId();
        change.campaignId = campaign.getId();
        change.campaignVersion = campaign.getVersion();
        change.actorId = actorId;
        change.occurredAt = now;
        change.kind = kind;
        return change;
    }

    public static GroupingChange completion(GroupingCampaign campaign, UUID actor) {
        var change = base(campaign, actor, campaign.getCompletedAt(), "COMPLETION_TRANSITION");
        change.fromStatus = "DISTRIBUTING";
        change.toStatus = "COMPLETED";
        return change;
    }

    public static GroupingChange logistics(
            GroupingCampaign campaign, UUID actor, GroupingCampaignStatus from, Instant now) {
        var change = base(campaign, actor, now, "LOGISTICS_TRANSITION");
        change.fromStatus = from.name();
        change.toStatus = campaign.getStatus().name();
        return change;
    }

    public static GroupingChange price(
            GroupingCampaign campaign,
            GroupingOffer offer,
            UUID actor,
            BigDecimal oldPrice,
            Instant now) {
        var change = base(campaign, actor, now, "PRICE_CHANGED");
        change.offerId = offer.getId();
        change.priceRevision = offer.getPriceRevision();
        change.oldPrice = oldPrice;
        change.newPrice = offer.getUnitPrice();
        change.currency = offer.getCurrency();
        return change;
    }

    public static GroupingChange deadline(
            GroupingCampaign campaign, UUID actor, Instant oldDeadline, Instant now) {
        var change = base(campaign, actor, now, "DEADLINE_CHANGED");
        change.oldClosesAt = oldDeadline;
        change.newClosesAt = campaign.getClosesAt();
        return change;
    }
}
