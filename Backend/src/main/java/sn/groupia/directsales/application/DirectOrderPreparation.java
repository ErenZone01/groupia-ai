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

/** Financially guarded start of internal preparation; never permits pickup or delivery. */
@Service
public class DirectOrderPreparation {
    record Order(
            String number,
            String status,
            String currency,
            Long acceptedTotalXof,
            Instant confirmedAt,
            Instant preparingAt) {}

    record Settlement(
            UUID requestId,
            UUID paymentId,
            BigDecimal amount,
            String currency,
            String provider,
            String transactionId,
            Instant confirmedAt) {}

    public record Receipt(String orderNumber, String status, Instant preparingAt) {}

    private final JdbcTemplate jdbc;
    private final OrganizationAccess access;
    private final PaymentOperations payments;
    private final Clock clock;

    public DirectOrderPreparation(
            JdbcTemplate jdbc, OrganizationAccess access, PaymentOperations payments, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.payments = payments;
        this.clock = clock;
    }

    @Transactional(timeout = 15)
    public Receipt start(UUID organizationId, UUID orderId) {
        UUID actor =
                access.requireActorPermission(
                        organizationId, OrganizationPermission.MANAGE_FULFILLMENT);
        // Match the storefront -> order locking order used by checkout and cancellation.
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
                        "select order_number,status,currency,accepted_total_xof,"
                                + "payment_confirmed_at,preparing_at from direct_orders"
                                + " where organization_id=? and id=? and storefront_id=? for update",
                        (rs, row) -> {
                            Timestamp confirmed = rs.getTimestamp("payment_confirmed_at");
                            Timestamp preparing = rs.getTimestamp("preparing_at");
                            return new Order(
                                    rs.getString("order_number"),
                                    rs.getString("status"),
                                    rs.getString("currency"),
                                    rs.getObject("accepted_total_xof", Long.class),
                                    confirmed == null ? null : confirmed.toInstant(),
                                    preparing == null ? null : preparing.toInstant());
                        },
                        organizationId,
                        orderId,
                        storefronts.getFirst());
        if (orders.isEmpty()) {
            throw notFound();
        }
        Order order = orders.getFirst();
        if ("PREPARING".equals(order.status())) {
            return new Receipt(order.number(), "PREPARING", order.preparingAt());
        }
        if (!"PAYMENT_CONFIRMED".equals(order.status())
                || !"XOF".equals(order.currency())
                || order.acceptedTotalXof() == null
                || order.acceptedTotalXof() <= 0
                || order.confirmedAt() == null) {
            throw blocked();
        }

        List<Settlement> settlements =
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
        if (settlements.size() != 1) {
            throw blocked();
        }
        Settlement saved = settlements.getFirst();
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
                || !Objects.equals(order.confirmedAt(), proof.confirmedAt())
                || clock.instant().isBefore(proof.confirmedAt())) {
            throw blocked();
        }

        Instant now = clock.instant();
        int updated =
                jdbc.update(
                        "update direct_orders d set status='PREPARING',preparing_at=?,"
                                + "preparing_by=?,version=version+1"
                                + " where d.organization_id=? and d.id=?"
                                + " and d.status='PAYMENT_CONFIRMED'"
                                + " and d.payment_confirmed_at=?"
                                + " and exists(select 1 from direct_order_verified_settlements v"
                                + " where v.organization_id=d.organization_id"
                                + " and v.direct_order_id=d.id and v.payment_request_id=?"
                                + " and v.payment_id=? and v.amount_xof=d.accepted_total_xof)",
                        Timestamp.from(now),
                        actor,
                        organizationId,
                        orderId,
                        Timestamp.from(proof.confirmedAt()),
                        saved.requestId(),
                        saved.paymentId());
        if (updated != 1) {
            throw blocked();
        }
        return new Receipt(order.number(), "PREPARING", now);
    }

    private static ApiException notFound() {
        return new ApiException(404, "DIRECT_ORDER_NOT_FOUND", "Commande introuvable.");
    }

    private static ApiException blocked() {
        return new ApiException(
                409,
                "DIRECT_ORDER_PREPARATION_BLOCKED",
                "La préparation nécessite un règlement vérifié, sans anomalie financière.");
    }
}
