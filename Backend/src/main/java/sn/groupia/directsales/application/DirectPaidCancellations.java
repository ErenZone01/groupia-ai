package sn.groupia.directsales.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.organization.api.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.api.event.CommercialRefundRecorded;
import sn.groupia.shared.api.ApiException;

@Service
public class DirectPaidCancellations {
    private final JdbcTemplate jdbc;
    private final OrganizationAccess access;
    private final CommercialRefunds refunds;
    private final Clock clock;

    public DirectPaidCancellations(
            JdbcTemplate j, OrganizationAccess a, CommercialRefunds r, Clock c) {
        jdbc = j;
        access = a;
        refunds = r;
        clock = c;
    }

    private record Order(String status, long version, UUID refund) {}

    @Transactional(readOnly = true)
    public CommercialCancellationView read(UUID org, UUID id) {
        access.requirePermission(org, OrganizationPermission.VIEW_ORDERS);
        return view(org, id, find(org, id, false));
    }

    @Transactional
    public CommercialCancellationView cancel(
            UUID org, UUID id, UUID key, CommercialCancellationCommand command) {
        UUID actor = access.requireActorPermission(org, OrganizationPermission.MANAGE_FULFILLMENT);
        if (command == null
                || !command.confirmed()
                || command.version() == null
                || command.version() < 0)
            throw new ApiException(
                    400,
                    "CANCELLATION_CONFIRMATION_REQUIRED",
                    "Confirmez l'annulation et la version de la commande.");
        lockStorefront(org, id);
        var order = find(org, id, true);
        var previous = refunds.find(org, PaymentTarget.directOrder(id));
        if (previous == null && (!eligible(order.status()) || order.version() != command.version()))
            throw new ApiException(
                    409,
                    "DIRECT_CANCELLATION_BLOCKED",
                    "La commande a changé ou a déjà été remise. Rechargez-la.");
        var refund =
                refunds.open(
                        org,
                        PaymentTarget.directOrder(id),
                        actor,
                        command.reason(),
                        key,
                        command.version());
        if (previous == null) {
            if (refund.collectedPrincipal().signum() <= 0)
                throw new IllegalStateException("Confirmed direct order has no settled principal");
            jdbc.update(
                    "update direct_orders set status=?,cancelled_at=?,cancelled_by=?,cancellation_case_id=?,version=version+1 where organization_id=? and id=?",
                    refund.amountDue().signum() > 0 ? "REFUND_PENDING" : "REFUNDED",
                    Timestamp.from(clock.instant()),
                    actor,
                    refund.id(),
                    org,
                    id);
        }
        return view(org, id, find(org, id, false));
    }

    @ApplicationModuleListener
    public void refundRecorded(CommercialRefundRecorded event) {
        if (event.target().type() != PaymentTargetType.DIRECT_ORDER) return;
        var refund = refunds.find(event.organizationId(), event.target());
        if (refund == null
                || !refund.id().equals(event.caseId())
                || !refund.status().equals("EXTERNALLY_RECORDED")
                || !refund.currency().equals(event.currency())
                || refund.amountDue().compareTo(event.amount()) != 0)
            throw new IllegalStateException("Direct refund event mismatch");
        lockStorefront(event.organizationId(), event.target().id());
        var order = find(event.organizationId(), event.target().id(), true);
        if (!Objects.equals(order.refund(), refund.id()))
            throw new IllegalStateException("Direct cancellation case mismatch");
        if (order.status().equals("REFUNDED")) return;
        if (!order.status().equals("REFUND_PENDING"))
            throw new IllegalStateException("Unexpected direct refund status");
        jdbc.update(
                "update direct_orders set status='REFUNDED',version=version+1 where organization_id=? and id=?",
                event.organizationId(),
                event.target().id());
    }

    private CommercialCancellationView view(UUID org, UUID id, Order order) {
        var refund = refunds.find(org, PaymentTarget.directOrder(id));
        return new CommercialCancellationView(
                refund == null ? null : refund.id(),
                refundStatus(refund),
                order.status(),
                order.version(),
                refund == null
                        && eligible(order.status())
                        && access.hasPermission(org, OrganizationPermission.MANAGE_FULFILLMENT),
                eligible(order.status())
                        ? null
                        : "L'annulation payée est possible avant l'expédition ou la remise.");
    }

    private static String refundStatus(CommercialRefunds.CaseView refund) {
        return refund == null ? null : refund.status();
    }

    private static boolean eligible(String status) {
        return Set.of("PAYMENT_CONFIRMED", "PREPARING", "READY_FOR_PICKUP", "READY_FOR_DISPATCH")
                .contains(status);
    }

    private void lockStorefront(UUID org, UUID id) {
        var rows =
                jdbc.query(
                        "select s.id from direct_storefronts s join direct_orders o on o.storefront_id=s.id and o.organization_id=s.organization_id where o.organization_id=? and o.id=? for update of s",
                        (rs, n) -> rs.getObject("id", UUID.class),
                        org,
                        id);
        if (rows.size() != 1) throw missing();
    }

    private Order find(UUID org, UUID id, boolean lock) {
        var rows =
                jdbc.query(
                        "select status,version,cancellation_case_id from direct_orders where organization_id=? and id=?"
                                + (lock ? " for update" : ""),
                        (rs, n) ->
                                new Order(
                                        rs.getString("status"),
                                        rs.getLong("version"),
                                        rs.getObject("cancellation_case_id", UUID.class)),
                        org,
                        id);
        if (rows.size() != 1) throw missing();
        return rows.getFirst();
    }

    private static ApiException missing() {
        return new ApiException(404, "DIRECT_ORDER_NOT_FOUND", "Commande introuvable.");
    }
}
