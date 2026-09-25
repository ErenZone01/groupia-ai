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

@Service @Transactional(readOnly = true)
public class OrderQueries {
    private final Orders orders;
    private final OrganizationAccess access;
    private final CheckoutSecrets secrets;
    private final Clock clock;
    OrderQueries(Orders orders, OrganizationAccess access, CheckoutSecrets secrets, Clock clock) {
        this.orders = orders; this.access = access; this.secrets = secrets; this.clock = clock;
    }
    public OrderReceipt track(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) { throw trackingInvalid(); }
        var order = orders.findByTrackingHash(secrets.hash(token)).filter(o -> o.allowsTrackingAt(clock.instant()))
                .orElseThrow(this::trackingInvalid);
        return OrderViews.receipt(order, null);
    }
    public OrganizerOrderView get(UUID org, UUID id) {
        access.requirePermission(org, OrganizationPermission.VIEW_ORDERS);
        return OrderViews.organizer(orders.find(org, id)
                .orElseThrow(() -> new ApiException(404, "ORDER_NOT_FOUND", "Commande introuvable.")));
    }
    public OrderPage list(UUID org, UUID campaign, String status, int page, int size) {
        access.requirePermission(org, OrganizationPermission.VIEW_ORDERS);
        if (page < 0 || page > 1000000 || size < 1 || size > 100) { throw invalidFilter(); }
        OrderStatus filter = null;
        if (status != null) {
            try { filter = OrderStatus.valueOf(status); }
            catch (IllegalArgumentException ex) { throw invalidFilter(); }
        }
        var result = orders.list(org, campaign, filter, page, size);
        return new OrderPage(result.items().stream().map(o -> new OrderSummary(o.getId(), o.getCampaignId(),
                o.getOrderNumber(), o.getStatus().name(), o.getFulfillmentStatus().name(), o.getInitialAmountDue(),
                o.getCurrency(), o.getPlacedAt())).toList(), page, size, result.totalElements(), result.totalPages());
    }
    private ApiException trackingInvalid() { return new ApiException(404, "ORDER_TRACKING_INVALID", "Accès de suivi invalide ou expiré."); }
    private ApiException invalidFilter() { return new ApiException(400, "INVALID_REQUEST", "Filtres ou pagination invalides."); }
}
