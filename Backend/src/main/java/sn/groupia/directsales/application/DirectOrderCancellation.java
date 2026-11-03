package sn.groupia.directsales.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.organization.api.OrganizationAccess;
import sn.groupia.organization.api.OrganizationPermission;
import sn.groupia.payment.api.PaymentOperations;
import sn.groupia.shared.api.ApiException;

/** Merchant cancellation of unpaid direct orders, serializing with payment preparation. */
@Service
public class DirectOrderCancellation {
    record Order(String number, String status, Instant cancelledAt) {}

    public record Receipt(String orderNumber, String status, Instant cancelledAt) {}

    private final JdbcTemplate jdbc;
    private final OrganizationAccess access;
    private final PaymentOperations payments;
    private final Clock clock;

    public DirectOrderCancellation(
            JdbcTemplate jdbc, OrganizationAccess access, PaymentOperations payments, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.payments = payments;
        this.clock = clock;
    }

    @Transactional(timeout = 15)
    public Receipt cancel(UUID organizationId, UUID orderId) {
        UUID actor =
                access.requireActorPermission(
                        organizationId, OrganizationPermission.MANAGE_FULFILLMENT);
        // Preserve the same lock order as DirectPaymentObligations.prepare().
        List<UUID> stores =
                jdbc.query(
                        "select s.id from direct_storefronts s"
                                + " join direct_orders d on d.organization_id=s.organization_id"
                                + " and d.storefront_id=s.id"
                                + " where d.organization_id=? and d.id=? for update of s",
                        (rs, row) -> rs.getObject("id", UUID.class),
                        organizationId,
                        orderId);
        if (stores.isEmpty()) {
            throw notFound();
        }
        List<Order> orders =
                jdbc.query(
                        "select order_number,status,cancelled_at from direct_orders"
                                + " where organization_id=? and id=? and storefront_id=? for update",
                        (rs, row) -> {
                            Timestamp cancelled = rs.getTimestamp("cancelled_at");
                            return new Order(
                                    rs.getString("order_number"),
                                    rs.getString("status"),
                                    cancelled == null ? null : cancelled.toInstant());
                        },
                        organizationId,
                        orderId,
                        stores.getFirst());
        if (orders.isEmpty()) {
            throw notFound();
        }
        Order order = orders.getFirst();
        if ("CANCELLED".equals(order.status())) {
            return new Receipt(order.number(), "CANCELLED", order.cancelledAt());
        }
        if (!"PENDING_PAYMENT_INTEGRATION".equals(order.status())) {
            throw blocked();
        }

        Boolean hasSettlement =
                jdbc.queryForObject(
                        "select exists(select 1 from direct_order_verified_settlements"
                                + " where organization_id=? and direct_order_id=?)",
                        Boolean.class,
                        organizationId,
                        orderId);
        if (!Boolean.FALSE.equals(hasSettlement)) {
            throw blocked();
        }

        List<UUID> requests =
                jdbc.query(
                        "select id from payment_requests"
                                + " where organization_id=? and direct_order_id=?"
                                + " and purpose='DIRECT_ORDER_INITIAL'",
                        (rs, row) -> rs.getObject("id", UUID.class),
                        organizationId,
                        orderId);
        if (requests.size() > 1) {
            throw blocked();
        }
        if (!requests.isEmpty()) {
            payments.cancelDirectUnpaidRequest(organizationId, requests.getFirst(), orderId);
        }
        Instant now = clock.instant();
        int updated =
                jdbc.update(
                        "update direct_orders set status='CANCELLED',cancelled_at=?,"
                                + "cancelled_by=?,version=version+1"
                                + " where organization_id=? and id=?"
                                + " and status='PENDING_PAYMENT_INTEGRATION'",
                        Timestamp.from(now),
                        actor,
                        organizationId,
                        orderId);
        if (updated != 1) {
            throw blocked();
        }
        return new Receipt(order.number(), "CANCELLED", now);
    }

    private static ApiException notFound() {
        return new ApiException(404, "DIRECT_ORDER_NOT_FOUND", "Commande introuvable.");
    }

    private static ApiException blocked() {
        return new ApiException(
                409,
                "DIRECT_ORDER_CANCELLATION_BLOCKED",
                "Cette commande ne peut pas être annulée sans vérification financière.");
    }
}
