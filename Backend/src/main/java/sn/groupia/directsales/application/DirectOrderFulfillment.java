package sn.groupia.directsales.application;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.organization.api.OrganizationAccess;
import sn.groupia.organization.api.OrganizationPermission;
import sn.groupia.payment.api.AcceptanceStatus;
import sn.groupia.payment.api.PaymentOperationalStatus;
import sn.groupia.payment.api.PaymentOperations;
import sn.groupia.payment.api.PaymentPurpose;
import sn.groupia.payment.api.PaymentStatus;
import sn.groupia.payment.api.PaymentTarget;
import sn.groupia.payment.api.dto.PaymentSettlementEvidence;
import sn.groupia.shared.api.ApiException;

/** Records a physical dispatch or handoff only after locking and revalidating settlement. */
@Service
public class DirectOrderFulfillment {
    record Order(
            String number,
            String status,
            String method,
            String currency,
            Long acceptedTotalXof,
            Instant confirmedAt,
            Instant preparingAt,
            Instant readyAt,
            Instant dispatchedAt) {}

    record Settlement(
            UUID requestId,
            UUID paymentId,
            BigDecimal amount,
            String currency,
            String provider,
            String transactionId,
            Instant confirmedAt) {}

    public record Receipt(
            String orderNumber, String status, String fulfillmentMethod, Instant occurredAt) {}

    public enum Action {
        DISPATCH("DELIVERY", "READY_FOR_DISPATCH", "DISPATCHED", "dispatched"),
        DELIVER("DELIVERY", "DISPATCHED", "DELIVERED", "completed"),
        PICKUP("PICKUP", "READY_FOR_PICKUP", "PICKED_UP", "completed");

        final String method;
        final String previousStatus;
        final String nextStatus;
        final String timestampPrefix;

        Action(String method, String previousStatus, String nextStatus, String timestampPrefix) {
            this.method = method;
            this.previousStatus = previousStatus;
            this.nextStatus = nextStatus;
            this.timestampPrefix = timestampPrefix;
        }
    }

    private final JdbcTemplate jdbc;
    private final OrganizationAccess access;
    private final PaymentOperations payments;
    private final Clock clock;

    public DirectOrderFulfillment(
            JdbcTemplate jdbc, OrganizationAccess access, PaymentOperations payments, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.payments = payments;
        this.clock = clock;
    }

    @Transactional(timeout = 15)
    public Receipt fulfill(UUID organizationId, UUID orderId, Action action, boolean confirmed) {
        UUID actor =
                access.requireActorPermission(
                        organizationId, OrganizationPermission.MANAGE_FULFILLMENT);
        if (action == null || !confirmed) {
            throw new ApiException(400, "DIRECT_FULFILLMENT_CONFIRMATION_REQUIRED",
                    "Confirmez que cette étape physique a bien été effectuée.");
        }
        // Same storefront -> order -> obligation lock order as preparation and cancellation.
        List<UUID> storefronts =
                jdbc.query(
                        "select s.id from direct_storefronts s"
                                + " join direct_orders d on d.organization_id=s.organization_id"
                                + " and d.storefront_id=s.id"
                                + " where d.organization_id=? and d.id=? for update of s",
                        (rs, row) -> rs.getObject("id", UUID.class),
                        organizationId,
                        orderId);
        if (storefronts.isEmpty()) {
            throw notFound();
        }
        List<Order> orders =
                jdbc.query(
                        "select order_number,status,fulfillment_method,currency,accepted_total_xof,"
                                + "payment_confirmed_at,preparing_at,ready_at,dispatched_at from direct_orders"
                                + " where organization_id=? and id=? and storefront_id=? for update",
                        (rs, row) -> {
                            Timestamp paymentConfirmed = rs.getTimestamp("payment_confirmed_at");
                            Timestamp preparing = rs.getTimestamp("preparing_at");
                            Timestamp ready = rs.getTimestamp("ready_at");
                            Timestamp dispatched = rs.getTimestamp("dispatched_at");
                            return new Order(
                                    rs.getString("order_number"),
                                    rs.getString("status"),
                                    rs.getString("fulfillment_method"),
                                    rs.getString("currency"),
                                    rs.getObject("accepted_total_xof", Long.class),
                                    paymentConfirmed == null ? null : paymentConfirmed.toInstant(),
                                    preparing == null ? null : preparing.toInstant(),
                                    ready == null ? null : ready.toInstant(),
                                    dispatched == null ? null : dispatched.toInstant());
                        },
                        organizationId,
                        orderId,
                        storefronts.getFirst());
        if (orders.isEmpty()) {
            throw notFound();
        }
        Order order = orders.getFirst();
        // A retry only returns the original receipt. It never repeats a physical action,
        // changes history or grants permission for the next action.
        List<Receipt> previous = jdbc.query(
                "select status,occurred_at from direct_order_fulfillment_events"
                        + " where organization_id=? and direct_order_id=? and action=?",
                (rs, row) -> new Receipt(order.number(), rs.getString("status"), order.method(),
                        rs.getTimestamp("occurred_at").toInstant()),
                organizationId, orderId, action.name());
        if (!previous.isEmpty()) return previous.getFirst();
        if (!action.previousStatus.equals(order.status())
                || !action.method.equals(order.method())
                || !"XOF".equals(order.currency())
                || order.acceptedTotalXof() == null || order.acceptedTotalXof() <= 0
                || order.confirmedAt() == null || order.preparingAt() == null
                || order.readyAt() == null
                || (action == Action.DELIVER && order.dispatchedAt() == null)) {
            throw blocked();
        }

        List<Settlement> receipts =
                jdbc.query(
                        "select payment_request_id,payment_id,amount_xof,currency,provider,"
                                + "provider_transaction_id,confirmed_at"
                                + " from direct_order_verified_settlements"
                                + " where organization_id=? and direct_order_id=?",
                        (rs, row) ->
                                new Settlement(
                                        rs.getObject("payment_request_id", UUID.class),
                                        rs.getObject("payment_id", UUID.class),
                                        rs.getBigDecimal("amount_xof"),
                                        rs.getString("currency"),
                                        rs.getString("provider"),
                                        rs.getString("provider_transaction_id"),
                                        rs.getTimestamp("confirmed_at").toInstant()),
                        organizationId,
                        orderId);
        if (receipts.size() != 1) {
            throw blocked();
        }
        Settlement saved = receipts.getFirst();
        if (saved.requestId() == null || saved.paymentId() == null) {
            throw blocked();
        }
        var state = payments.lockRequestState(organizationId, saved.requestId());
        if (state == null
                || state.request() == null
                || state.operationalStatus() != PaymentOperationalStatus.SETTLED
                || state.attemptInProgress()
                || state.reconciliationRequired()) {
            throw blocked();
        }
        var request = state.request();
        BigDecimal total = BigDecimal.valueOf(order.acceptedTotalXof());
        if (!Objects.equals(request.id(), saved.requestId())
                || !Objects.equals(request.organizationId(), organizationId)
                || !Objects.equals(request.target(), PaymentTarget.directOrder(orderId))
                || request.purpose() != PaymentPurpose.DIRECT_ORDER_INITIAL
                || request.acceptanceStatus() != AcceptanceStatus.CLOSED
                || !Objects.equals(request.settledPaymentId(), saved.paymentId())
                || !"XOF".equals(request.currency())
                || request.expectedAmount() == null
                || request.expectedAmount().compareTo(total) != 0) {
            throw blocked();
        }
        PaymentSettlementEvidence proof =
                payments.settlementEvidence(organizationId, saved.requestId());
        if (proof == null
                || !Objects.equals(proof.organizationId(), organizationId)
                || !Objects.equals(proof.paymentRequestId(), saved.requestId())
                || !Objects.equals(proof.paymentId(), saved.paymentId())
                || !Objects.equals(proof.target(), PaymentTarget.directOrder(orderId))
                || proof.purpose() != PaymentPurpose.DIRECT_ORDER_INITIAL
                || proof.acceptanceStatus() != AcceptanceStatus.CLOSED
                || proof.status() != PaymentStatus.SUCCEEDED
                || proof.reversal()
                || !"XOF".equals(proof.currency())
                || proof.amount() == null
                || proof.amount().compareTo(total) != 0
                || saved.amount() == null
                || saved.amount().compareTo(total) != 0
                || !"XOF".equals(saved.currency())
                || !Objects.equals(saved.provider(), proof.provider())
                || !Objects.equals(saved.transactionId(), proof.providerTransactionId())
                || proof.provider() == null
                || proof.provider().isBlank()
                || proof.providerTransactionId() == null
                || proof.providerTransactionId().isBlank()
                || !Objects.equals(saved.confirmedAt(), proof.confirmedAt())
                || !Objects.equals(order.confirmedAt(), proof.confirmedAt())) {
            throw blocked();
        }
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (now.isBefore(order.readyAt()) || now.isBefore(order.preparingAt())
                || now.isBefore(proof.confirmedAt())
                || (action == Action.DELIVER && now.isBefore(order.dispatchedAt()))) {
            throw blocked();
        }
        // The only interpolated identifiers come from the closed Action enum.
        int updated = jdbc.update(
                "update direct_orders set status=?," + action.timestampPrefix + "_at=?,"
                        + action.timestampPrefix + "_by=?,version=version+1"
                        + " where organization_id=? and id=? and status=? and fulfillment_method=?",
                action.nextStatus, Timestamp.from(now), actor, organizationId, orderId,
                action.previousStatus, action.method);
        if (updated != 1) throw blocked();
        jdbc.update(
                "insert into direct_order_fulfillment_events"
                        + " (id,organization_id,direct_order_id,action,status,actor_id,occurred_at,"
                        + "payment_request_id,payment_id) values (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), organizationId, orderId, action.name(), action.nextStatus,
                actor, Timestamp.from(now), saved.requestId(), saved.paymentId());
        return new Receipt(order.number(), action.nextStatus, order.method(), now);
    }

    private static ApiException notFound() {
        return new ApiException(404, "DIRECT_ORDER_NOT_FOUND", "Commande introuvable.");
    }

    private static ApiException blocked() {
        return new ApiException(
                409,
                "DIRECT_ORDER_FULFILLMENT_BLOCKED",
                "Cette étape nécessite une commande prête et un règlement vérifié, sans anomalie.");
    }
}
