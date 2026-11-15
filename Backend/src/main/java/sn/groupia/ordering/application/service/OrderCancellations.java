package sn.groupia.ordering.application.service;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.grouping.api.CampaignCancellationFacts;
import sn.groupia.grouping.api.event.GroupingCancelled;
import sn.groupia.ordering.api.event.OrderCancellationRequested;
import sn.groupia.ordering.domain.model.*;
import sn.groupia.ordering.domain.repository.Orders;
import sn.groupia.organization.api.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.api.event.CommercialRefundRecorded;
import sn.groupia.shared.api.ApiException;

@Service
public class OrderCancellations {
    private final Orders orders;
    private final CampaignCancellationFacts campaigns;
    private final CommercialRefunds refunds;
    private final OrganizationAccess access;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public OrderCancellations(
            Orders o,
            CampaignCancellationFacts c,
            CommercialRefunds r,
            OrganizationAccess a,
            JdbcTemplate j,
            ApplicationEventPublisher e,
            Clock k) {
        orders = o;
        campaigns = c;
        refunds = r;
        access = a;
        jdbc = j;
        events = e;
        clock = k;
    }

    @Transactional(readOnly = true)
    public CommercialCancellationView read(UUID org, UUID id) {
        access.requirePermission(org, OrganizationPermission.VIEW_ORDERS);
        var o = find(org, id);
        return view(o, campaigns.cancellation(org, o.getCampaignId()).status());
    }

    @Transactional
    public CommercialCancellationView cancel(
            UUID org, UUID id, UUID key, CommercialCancellationCommand command) {
        UUID actor = access.requireActorPermission(org, OrganizationPermission.MANAGE_FULFILLMENT);
        validate(command);
        var found = find(org, id);
        var campaign = campaigns.lockForOrderWorkflow(org, found.getCampaignId());
        var order = orders.lock(id).orElseThrow(OrderCancellations::missing);
        if (!order.getOrganizationId().equals(org)) throw missing();
        var previous = refunds.find(org, PaymentTarget.order(id));
        if (previous == null) {
            order.requireVersion(command.version());
            if (blocker(order, campaign.status()) != null) throw blocked();
        }
        var refund =
                refunds.open(
                        org,
                        PaymentTarget.order(id),
                        actor,
                        command.reason(),
                        key,
                        command.version());
        if (previous == null) {
            order.cancelWithRefundCase(
                    refund.id(),
                    refund.amountDue().signum() > 0,
                    refund.collectedPrincipal().signum() > 0,
                    clock.instant());
            orders.flush();
        }
        return view(order, campaign.status());
    }

    @ApplicationModuleListener
    public void campaignCancelled(GroupingCancelled event) {
        if (!campaigns
                .cancellation(event.organizationId(), event.campaignId())
                .status()
                .equals("CANCELLED"))
            throw new IllegalStateException("Campaign cancellation mismatch");
        // Each order has its own durable transaction. One uncertain payment cannot roll back its
        // siblings.
        var ids =
                jdbc.query(
                        "select id from orders where organization_id=? and campaign_id=? and status in ('PENDING_PAYMENT','CONFIRMED','EXPIRED') order by id",
                        (rs, n) -> rs.getObject("id", UUID.class),
                        event.organizationId(),
                        event.campaignId());
        for (var id : ids)
            events.publishEvent(
                    new OrderCancellationRequested(
                            UUID.nameUUIDFromBytes(
                                    (event.eventId() + ":" + id).getBytes(StandardCharsets.UTF_8)),
                            event.occurredAt(),
                            event.organizationId(),
                            event.campaignId(),
                            id,
                            event.actorId()));
    }

    @ApplicationModuleListener
    public void cancellationRequested(OrderCancellationRequested event) {
        var campaign = campaigns.lockForOrderWorkflow(event.organizationId(), event.campaignId());
        if (!campaign.status().equals("CANCELLED"))
            throw new IllegalStateException("Expected cancelled campaign");
        var order = orders.lock(event.orderId()).orElseThrow(OrderCancellations::missing);
        if (!order.getOrganizationId().equals(event.organizationId())
                || !order.getCampaignId().equals(event.campaignId())) throw missing();
        if (refunds.find(event.organizationId(), PaymentTarget.order(order.getId())) != null)
            return;
        if (blocker(order, campaign.status()) != null) throw blocked();
        var refund =
                refunds.open(
                        event.organizationId(),
                        PaymentTarget.order(order.getId()),
                        event.actorId(),
                        "Annulation du groupage",
                        event.eventId(),
                        order.getVersion());
        order.cancelWithRefundCase(
                refund.id(),
                refund.amountDue().signum() > 0,
                refund.collectedPrincipal().signum() > 0,
                clock.instant());
        orders.flush();
    }

    @ApplicationModuleListener
    public void paymentSettled(sn.groupia.payment.api.event.PaymentRequestSettled event) {
        if (event.purpose() != PaymentPurpose.INITIAL_ORDER
                && event.purpose() != PaymentPurpose.SHIPPING_BALANCE) return;
        var order = find(event.organizationId(), event.orderId());
        var cancellation = campaigns.cancellation(event.organizationId(), order.getCampaignId());
        if (cancellation.cancelled() && cancellation.actorId() != null) {
            cancellationRequested(
                    new OrderCancellationRequested(
                            UUID.nameUUIDFromBytes(
                                    ("resolved-cancellation:" + event.paymentId())
                                            .getBytes(StandardCharsets.UTF_8)),
                            event.occurredAt(),
                            event.organizationId(),
                            order.getCampaignId(),
                            order.getId(),
                            cancellation.actorId()));
        }
    }

    @ApplicationModuleListener
    public void refundRecorded(CommercialRefundRecorded event) {
        if (event.target().type() != PaymentTargetType.ORDER) return;
        var refund = refunds.find(event.organizationId(), event.target());
        if (refund == null
                || !refund.id().equals(event.caseId())
                || !refund.status().equals("EXTERNALLY_RECORDED")
                || !refund.currency().equals(event.currency())
                || refund.amountDue().compareTo(event.amount()) != 0)
            throw new IllegalStateException("Commercial refund event mismatch");
        var order = orders.lock(event.target().id()).orElseThrow(OrderCancellations::missing);
        if (!order.getOrganizationId().equals(event.organizationId())) throw missing();
        order.externalRefundRecorded(refund.id());
    }

    private CommercialCancellationView view(Order o, String campaign) {
        var refund = refunds.find(o.getOrganizationId(), PaymentTarget.order(o.getId()));
        var blocker = blocker(o, campaign);
        return new CommercialCancellationView(
                refund == null ? null : refund.id(),
                refund == null ? null : refund.status(),
                o.getStatus().name(),
                o.getVersion(),
                refund == null
                        && blocker == null
                        && access.hasPermission(
                                o.getOrganizationId(), OrganizationPermission.MANAGE_FULFILLMENT),
                blocker);
    }

    private static String blocker(Order o, String campaign) {
        if (!Set.of(OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED, OrderStatus.EXPIRED)
                        .contains(o.getStatus())
                || o.getFulfillmentStatus() == FulfillmentStatus.PICKED_UP)
            return "La commande est déjà remise ou annulée.";
        if (o.getStatus() == OrderStatus.CONFIRMED
                && !Set.of("DRAFT", "OPEN", "CLOSED", "CANCELLED").contains(campaign))
            return "La commande fournisseur a commencé. Un retour doit être traité séparément.";
        return null;
    }

    private Order find(UUID org, UUID id) {
        return orders.find(org, id).orElseThrow(OrderCancellations::missing);
    }

    private static void validate(CommercialCancellationCommand c) {
        if (c == null || !c.confirmed() || c.version() == null || c.version() < 0)
            throw new ApiException(
                    400,
                    "CANCELLATION_CONFIRMATION_REQUIRED",
                    "Confirmez l'annulation et la version de la commande.");
    }

    private static ApiException missing() {
        return new ApiException(404, "ORDER_NOT_FOUND", "Commande introuvable.");
    }

    private static ApiException blocked() {
        return new ApiException(
                409,
                "ORDER_CANCELLATION_BLOCKED",
                "L'annulation n'est plus disponible à cette étape.");
    }
}
