package sn.groupia.directsales.application;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.organization.api.*;
import sn.groupia.payment.api.*;
import sn.groupia.shared.api.ApiException;

/**
 * Physical handover is explicitly confirmed by an authorized operator after canonical settlement.
 */
@Service
public class DirectOrderFulfillment {
    public record Command(String action, Long version, boolean confirmed) {}

    public record Event(UUID id, String fromStatus, String toStatus, Instant occurredAt) {}

    record Snapshot(
            String status,
            String method,
            long version,
            BigDecimal amount,
            String currency,
            Instant confirmedAt,
            Instant readyAt,
            Instant dispatchedAt,
            Instant handedOverAt) {}

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

    @Transactional(readOnly = true)
    public List<Event> history(UUID org, UUID order) {
        access.requirePermission(org, OrganizationPermission.VIEW_ORDERS);
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists(select 1 from direct_orders where organization_id=? and id=?)",
                        Boolean.class,
                        org,
                        order))) throw missing();
        return jdbc.query(
                "select id,from_status,to_status,occurred_at from direct_order_fulfillment_events where organization_id=? and direct_order_id=? order by occurred_at,id",
                (rs, n) ->
                        new Event(
                                rs.getObject("id", UUID.class),
                                rs.getString("from_status"),
                                rs.getString("to_status"),
                                rs.getTimestamp("occurred_at").toInstant()),
                org,
                order);
    }

    @Transactional(timeout = 15)
    public Event transition(UUID org, UUID order, String key, Command command) {
        UUID actor = access.requireActorPermission(org, OrganizationPermission.MANAGE_FULFILLMENT);
        if (command == null
                || !command.confirmed()
                || command.version() == null
                || command.version() < 0
                || key == null
                || !key.matches("[A-Za-z0-9._:-]{16,128}"))
            throw new ApiException(
                    400,
                    "DIRECT_FULFILLMENT_CONFIRMATION_REQUIRED",
                    "Confirmez l'opération et la version de la commande.");
        String next =
                switch (command.action() == null ? "" : command.action()) {
                    case "DISPATCH" -> "DISPATCHED";
                    case "PICKUP" -> "PICKED_UP";
                    case "DELIVER" -> "DELIVERED";
                    case "COMPLETE" -> "COMPLETED";
                    default ->
                            throw new ApiException(
                                    400, "DIRECT_FULFILLMENT_ACTION_INVALID", "Action inconnue.");
                };
        var storefront =
                jdbc.query(
                        "select s.id from direct_storefronts s join direct_orders d on d.storefront_id=s.id and d.organization_id=s.organization_id where d.organization_id=? and d.id=? for update of s",
                        (rs, n) -> rs.getObject("id", UUID.class),
                        org,
                        order);
        if (storefront.size() != 1) throw missing();
        var rows =
                jdbc.query(
                        "select status,fulfillment_method,version,accepted_total_xof,currency,payment_confirmed_at,ready_at,dispatched_at,handed_over_at from direct_orders where organization_id=? and id=? for update",
                        (rs, n) ->
                                new Snapshot(
                                        rs.getString("status"),
                                        rs.getString("fulfillment_method"),
                                        rs.getLong("version"),
                                        rs.getBigDecimal("accepted_total_xof"),
                                        rs.getString("currency"),
                                        instant(rs.getTimestamp("payment_confirmed_at")),
                                        instant(rs.getTimestamp("ready_at")),
                                        instant(rs.getTimestamp("dispatched_at")),
                                        instant(rs.getTimestamp("handed_over_at"))),
                        org,
                        order);
        if (rows.size() != 1) throw missing();
        var old =
                jdbc.query(
                        "select id,from_status,to_status,occurred_at,expected_version from direct_order_fulfillment_events where organization_id=? and direct_order_id=? and idempotency_key=?",
                        (rs, n) -> {
                            if (!next.equals(rs.getString("to_status"))
                                    || command.version() != rs.getLong("expected_version"))
                                throw conflict();
                            return new Event(
                                    rs.getObject("id", UUID.class),
                                    rs.getString("from_status"),
                                    rs.getString("to_status"),
                                    rs.getTimestamp("occurred_at").toInstant());
                        },
                        org,
                        order,
                        key);
        if (!old.isEmpty()) return old.getFirst();
        var saved = rows.getFirst();
        if (saved.version() != command.version()) throw conflict();
        boolean allowed =
                switch (next) {
                    case "DISPATCHED" ->
                            saved.method().equals("DELIVERY")
                                    && saved.status().equals("READY_FOR_DISPATCH");
                    case "PICKED_UP" ->
                            saved.method().equals("PICKUP")
                                    && saved.status().equals("READY_FOR_PICKUP");
                    case "DELIVERED" ->
                            saved.method().equals("DELIVERY")
                                    && saved.status().equals("DISPATCHED");
                    default -> List.of("PICKED_UP", "DELIVERED").contains(saved.status());
                };
        if (!allowed
                || saved.readyAt() == null
                || saved.confirmedAt() == null
                || saved.amount() == null
                || saved.amount().signum() <= 0
                || !"XOF".equals(saved.currency())) throw blocked();
        var receipts =
                jdbc.queryForList(
                        "select payment_request_id,payment_id,amount_xof,currency,provider,provider_transaction_id,confirmed_at from direct_order_verified_settlements where organization_id=? and direct_order_id=?",
                        org,
                        order);
        if (receipts.size() != 1) throw blocked();
        var receipt = receipts.getFirst();
        UUID requestId = (UUID) receipt.get("payment_request_id");
        var state = payments.lockRequestState(org, requestId);
        if (state == null
                || state.request() == null
                || state.operationalStatus() != PaymentOperationalStatus.SETTLED
                || state.attemptInProgress()
                || state.reconciliationRequired()) throw blocked();
        var request = state.request();
        var proof = payments.settlementEvidence(org, requestId);
        if (proof == null
                || !Objects.equals(request.organizationId(), org)
                || !Objects.equals(request.id(), requestId)
                || !Objects.equals(request.target(), PaymentTarget.directOrder(order))
                || request.purpose() != PaymentPurpose.DIRECT_ORDER_INITIAL
                || request.acceptanceStatus() != AcceptanceStatus.CLOSED
                || !Objects.equals(request.settledPaymentId(), receipt.get("payment_id"))
                || !"XOF".equals(request.currency())
                || request.expectedAmount() == null
                || request.expectedAmount().compareTo(saved.amount()) != 0
                || !Objects.equals(proof.organizationId(), org)
                || !Objects.equals(proof.paymentRequestId(), requestId)
                || !Objects.equals(proof.target(), PaymentTarget.directOrder(order))
                || proof.purpose() != PaymentPurpose.DIRECT_ORDER_INITIAL
                || proof.status() != PaymentStatus.SUCCEEDED
                || proof.reversal()
                || proof.acceptanceStatus() != AcceptanceStatus.CLOSED
                || !Objects.equals(proof.paymentId(), receipt.get("payment_id"))
                || !Objects.equals(proof.provider(), receipt.get("provider"))
                || proof.providerTransactionId() == null
                || proof.providerTransactionId().isBlank()
                || !Objects.equals(
                        proof.providerTransactionId(), receipt.get("provider_transaction_id"))
                || !"XOF".equals(proof.currency())
                || !"XOF".equals(receipt.get("currency"))
                || proof.amount() == null
                || proof.amount().compareTo(saved.amount()) != 0
                || receipt.get("amount_xof") == null
                || ((BigDecimal) receipt.get("amount_xof")).compareTo(saved.amount()) != 0
                || !Objects.equals(proof.confirmedAt(), saved.confirmedAt())
                || receipt.get("confirmed_at") == null
                || !Objects.equals(
                        proof.confirmedAt(), ((Timestamp) receipt.get("confirmed_at")).toInstant()))
            throw blocked();
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant previous =
                next.equals("DELIVERED")
                        ? saved.dispatchedAt()
                        : next.equals("COMPLETED") ? saved.handedOverAt() : saved.readyAt();
        if (previous == null || now.isBefore(previous) || now.isBefore(proof.confirmedAt()))
            throw blocked();
        String column =
                next.equals("DISPATCHED")
                        ? "dispatched_at"
                        : next.equals("COMPLETED") ? "completed_at" : "handed_over_at";
        if (jdbc.update(
                        "update direct_orders set status=?,"
                                + column
                                + "=?,version=version+1 where organization_id=? and id=? and version=?",
                        next,
                        Timestamp.from(now),
                        org,
                        order,
                        command.version())
                != 1) throw conflict();
        UUID event = UUID.randomUUID();
        jdbc.update(
                "insert into direct_order_fulfillment_events(id,organization_id,direct_order_id,actor_id,idempotency_key,from_status,to_status,occurred_at,expected_version) values(?,?,?,?,?,?,?,?,?)",
                event,
                org,
                order,
                actor,
                key,
                saved.status(),
                next,
                Timestamp.from(now),
                command.version());
        return new Event(event, saved.status(), next, now);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static ApiException missing() {
        return new ApiException(404, "DIRECT_ORDER_NOT_FOUND", "Commande introuvable.");
    }

    private static ApiException conflict() {
        return new ApiException(
                409,
                "DIRECT_ORDER_VERSION_CONFLICT",
                "La commande ou cette intention a changé. Actualisez son état.");
    }

    private static ApiException blocked() {
        return new ApiException(
                409,
                "DIRECT_FULFILLMENT_BLOCKED",
                "La remise nécessite un paiement vérifié et une étape précédente confirmée.");
    }
}
