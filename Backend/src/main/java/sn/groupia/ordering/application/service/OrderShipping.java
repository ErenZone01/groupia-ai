package sn.groupia.ordering.application.service;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.grouping.api.SupplierCampaignLifecycle;
import sn.groupia.ordering.api.dto.OrderShippingRow;
import sn.groupia.ordering.domain.model.*;
import sn.groupia.ordering.domain.repository.Orders;
import sn.groupia.organization.api.*;
import sn.groupia.shared.api.ApiException;
import sn.groupia.shipping.api.ShippingAssessments;
import sn.groupia.shipping.api.dto.*;

/**
 * Coordinates trusted order snapshots with shipping; atomic calculation and shipping obligation.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class OrderShipping {
    private final ShippingFinance finance;
    private final Orders orders;
    private final OrganizationAccess access;
    private final SupplierCampaignLifecycle campaigns;
    private final ShippingAssessments shipping;

    OrderShipping(
            Orders orders,
            OrganizationAccess access,
            SupplierCampaignLifecycle campaigns,
            ShippingAssessments shipping,
            ShippingFinance finance) {
        this.finance = finance;
        this.orders = orders;
        this.access = access;
        this.campaigns = campaigns;
        this.shipping = shipping;
    }

    public List<OrderShippingRow> list(UUID org, UUID campaign) {
        access.requirePermission(org, OrganizationPermission.VIEW_ORDERS);
        readable(campaigns.read(org, campaign).status());
        var assessments = shipping.readAll(org, campaign);
        return orders.forDistribution(org, campaign).stream()
                .map(
                        o -> {
                            var seed = seed(o);
                            var a =
                                    assessments.getOrDefault(
                                            o.getId(),
                                            new ShippingAssessmentView(
                                                    null, "PENDING", seed, 0, null, null, null,
                                                    null, List.of(), List.of()));
                            var customer = o.getCustomerContactSnapshot();
                            return new OrderShippingRow(
                                    o.getId(),
                                    o.getOrderNumber(),
                                    customer.firstName() + " " + customer.lastName(),
                                    a,
                                    finance.read(o, a),
                                    o.getFulfillmentStatus().name());
                        })
                .toList();
    }

    public ShippingExplanation preview(UUID org, UUID campaign, UUID id, FinalShippingInput input) {
        access.requirePermission(org, OrganizationPermission.VIEW_ORDERS);
        access.requirePermission(org, OrganizationPermission.MANAGE_GROUPINGS);
        eligible(campaigns.read(org, campaign).status());
        return shipping.preview(seed(scoped(org, campaign, id)), input);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ShippingAssessmentView measure(
            UUID org, UUID campaign, UUID id, long version, MeasurementInput input) {
        var actor = authorizeWrite(org, campaign);
        var order = lock(org, campaign, id);
        requireNotReady(order);
        return shipping.measure(seed(order), version, input, actor);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ShippingAssessmentView finalizeShipping(
            UUID org, UUID campaign, UUID id, FinalShippingInput input) {
        var actor = authorizeWrite(org, campaign);
        var order = lock(org, campaign, id);
        requireNotReady(order);
        shipping.preview(seed(order), input);
        var previous = finance.prepareCorrection(order);
        var result = shipping.finalizeShipping(seed(order), input, actor);
        finance.synchronize(order, result, previous);
        return result;
    }

    private UUID authorizeWrite(UUID org, UUID campaign) {
        access.requirePermission(org, OrganizationPermission.VIEW_ORDERS);
        var actor = access.requireActorPermission(org, OrganizationPermission.MANAGE_GROUPINGS);
        // Same lock order as supplier capture: campaign, then orders.
        eligible(campaigns.lock(org, campaign).status());
        return actor;
    }

    private Order lock(UUID org, UUID campaign, UUID id) {
        scoped(
                org, campaign,
                id); // Check ownership before locking an identifier supplied by the caller.
        var order = orders.lock(id).orElseThrow(OrderShipping::missing);
        checkOrder(order, org, campaign);
        return order;
    }

    private Order scoped(UUID org, UUID campaign, UUID id) {
        var order = orders.find(org, id).orElseThrow(OrderShipping::missing);
        checkOrder(order, org, campaign);
        return order;
    }

    private static void checkOrder(Order order, UUID org, UUID campaign) {
        if (!order.getOrganizationId().equals(org) || !order.getCampaignId().equals(campaign))
            throw missing();
        requireNotReady(order);
        if (order.getStatus() != OrderStatus.CONFIRMED)
            throw new ApiException(
                    409,
                    "SHIPPING_ORDER_NOT_ELIGIBLE",
                    "Seules les commandes confirmées peuvent être traitées.");
    }

    private static void requireNotReady(Order order) {
        if (order.getFulfillmentStatus() != FulfillmentStatus.NOT_READY)
            throw new ApiException(
                    409,
                    "ORDER_FULFILLMENT_LOCKED",
                    "Cette commande est déjà prête pour retrait ou retirée. Retirez d'abord sa mise à disposition avant de corriger le transport.");
    }

    private static void readable(String status) {
        if (!"COMPLETED".equals(status)) eligible(status);
    }

    private static void eligible(String status) {
        if (!Set.of("SUPPLIER_ORDERED", "IN_TRANSIT", "ARRIVED", "DISTRIBUTING").contains(status))
            throw new ApiException(
                    409,
                    "SHIPPING_CAMPAIGN_NOT_ELIGIBLE",
                    "Le transport est disponible après la commande fournisseur.");
    }

    static AssessmentSeed seed(Order o) {
        return new AssessmentSeed(
                o.getOrganizationId(),
                o.getCampaignId(),
                o.getId(),
                o.getShippingTermsSnapshot(),
                o.getItems().stream().mapToLong(OrderItem::getQuantity).sum(),
                estimate(o, OrderItem::getEstimatedUnitWeightKgSnapshot),
                estimate(o, OrderItem::getEstimatedUnitVolumeM3Snapshot),
                o.getEstimatedShippingAmount());
    }

    private static BigDecimal estimate(Order o, Function<OrderItem, BigDecimal> unit) {
        if (o.getItems().stream().anyMatch(i -> unit.apply(i) == null)) return null;
        return o.getItems().stream()
                .map(i -> unit.apply(i).multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static ApiException missing() {
        return new ApiException(404, "ORDER_NOT_FOUND", "Commande introuvable dans ce groupage.");
    }
}
