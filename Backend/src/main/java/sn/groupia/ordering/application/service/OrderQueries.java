package sn.groupia.ordering.application.service;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.ordering.api.dto.*;
import sn.groupia.ordering.domain.model.OrderStatus;
import sn.groupia.ordering.domain.repository.Orders;
import sn.groupia.organization.api.*;
import sn.groupia.shared.api.ApiException;

@Service
@Transactional(readOnly = true)
public class OrderQueries {
    private final ShippingFinance finance;
    private final sn.groupia.shipping.api.ShippingAssessments shipping;
    private final Orders orders;
    private final OrganizationAccess access;
    private final CheckoutSecrets secrets;
    private final Clock clock;
    private final sn.groupia.grouping.api.GroupingProgressQueries progress;

    OrderQueries(
            Orders orders,
            OrganizationAccess access,
            CheckoutSecrets secrets,
            Clock clock,
            sn.groupia.grouping.api.GroupingProgressQueries progress,
            sn.groupia.shipping.api.ShippingAssessments shipping,
            ShippingFinance finance) {
        this.finance = finance;
        this.shipping = shipping;
        this.orders = orders;
        this.access = access;
        this.secrets = secrets;
        this.clock = clock;
        this.progress = progress;
    }

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public OrderTrackingView track(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw trackingInvalid();
        }
        var order =
                orders.findByTrackingHash(secrets.hash(token))
                        .filter(o -> o.allowsTrackingAt(clock.instant()))
                        .orElseThrow(this::trackingInvalid);
        return OrderTrackingView.from(
                OrderViews.receipt(order, null),
                progress.progress(order.getOrganizationId(), order.getCampaignId()),
                shipping.currentFinal(order.getOrganizationId(), order.getId()),
                finance.read(order),
                order.getReadyForPickupAt(),
                order.getPickedUpAt());
    }

    public OrganizerOrderView get(UUID org, UUID id) {
        access.requirePermission(org, OrganizationPermission.VIEW_ORDERS);
        return OrderViews.organizer(
                orders.find(org, id)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                404, "ORDER_NOT_FOUND", "Commande introuvable.")));
    }

    public OrderPage list(UUID org, UUID campaign, String status, int page, int size) {
        access.requirePermission(org, OrganizationPermission.VIEW_ORDERS);
        if (page < 0 || page > 1000000 || size < 1 || size > 100) {
            throw invalidFilter();
        }
        OrderStatus filter = null;
        if (status != null) {
            try {
                filter = OrderStatus.valueOf(status);
            } catch (IllegalArgumentException ex) {
                throw invalidFilter();
            }
        }
        var result = orders.list(org, campaign, filter, page, size);
        var preview =
                orders.productPreviews(org, result.items().stream().map(o -> o.getId()).toList());
        return new OrderPage(
                result.items().stream()
                        .map(
                                o -> {
                                    var productIds =
                                            preview.getOrDefault(o.getId(), java.util.List.of());
                                    return new OrderSummary(
                                            o.getId(),
                                            o.getCampaignId(),
                                            o.getOrderNumber(),
                                            o.getStatus().name(),
                                            o.getFulfillmentStatus().name(),
                                            o.getInitialAmountDue(),
                                            o.getCurrency(),
                                            o.getPlacedAt(),
                                            productIds.stream().limit(3).toList(),
                                            productIds.size());
                                })
                        .toList(),
                page,
                size,
                result.totalElements(),
                result.totalPages());
    }

    private ApiException trackingInvalid() {
        return new ApiException(
                404, "ORDER_TRACKING_INVALID", "Accès de suivi invalide ou expiré.");
    }

    private ApiException invalidFilter() {
        return new ApiException(400, "INVALID_REQUEST", "Filtres ou pagination invalides.");
    }
}
