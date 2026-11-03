package sn.groupia.directsales.application;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import sn.groupia.payment.api.AcceptanceStatus;
import sn.groupia.payment.api.PaymentOperationalStatus;
import sn.groupia.payment.api.PaymentOperations;
import sn.groupia.payment.api.PaymentPurpose;
import sn.groupia.payment.api.PaymentStatus;
import sn.groupia.payment.api.PaymentTarget;
import sn.groupia.payment.api.PaymentTargetType;
import sn.groupia.payment.api.dto.PaymentSettlementEvidence;
import sn.groupia.payment.api.event.PaymentObligationSettled;

/**
 * Records canonical payment evidence and marks direct orders financially confirmed. Neither this
 * state nor the durable receipt authorizes fulfillment or payouts.
 */
@Component
public class DirectOrderSettlementListener {
    private record Order(String status, String currency, long total, Instant confirmedAt) {}

    private record Receipt(
            UUID requestId,
            UUID paymentId,
            BigDecimal amount,
            String currency,
            String provider,
            String transactionId,
            Instant confirmedAt) {}

    private final JdbcTemplate jdbc;
    private final PaymentOperations payments;
    private final Clock clock;

    DirectOrderSettlementListener(JdbcTemplate jdbc, PaymentOperations payments, Clock clock) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.clock = clock;
    }

    @ApplicationModuleListener
    public void on(PaymentObligationSettled event) {
        if (event.target().type() != PaymentTargetType.DIRECT_ORDER) {
            return;
        }
        if (event.purpose() != PaymentPurpose.DIRECT_ORDER_INITIAL) {
            throw new IllegalStateException("Unexpected direct-order settlement purpose");
        }

        UUID org = event.organizationId();
        UUID directOrderId = event.target().id();
        // Never trust the event alone. The payment module owns verified gateway evidence.
        PaymentSettlementEvidence proof =
                payments.settlementEvidence(org, event.paymentRequestId());
        if (!Objects.equals(proof.organizationId(), org)
                || !Objects.equals(proof.paymentRequestId(), event.paymentRequestId())
                || !Objects.equals(proof.paymentId(), event.paymentId())
                || !Objects.equals(proof.target(), event.target())
                || proof.target().type() != PaymentTargetType.DIRECT_ORDER
                || proof.purpose() != PaymentPurpose.DIRECT_ORDER_INITIAL
                || proof.acceptanceStatus() != AcceptanceStatus.CLOSED
                || proof.status() != PaymentStatus.SUCCEEDED
                || proof.reversal()
                || !Objects.equals(proof.currency(), "XOF")
                || !Objects.equals(event.currency(), proof.currency())
                || proof.amount() == null
                || event.amount() == null
                || proof.amount().compareTo(event.amount()) != 0
                || proof.provider() == null
                || proof.provider().isBlank()
                || proof.providerTransactionId() == null
                || proof.providerTransactionId().isBlank()
                || proof.confirmedAt() == null
                || !proof.confirmedAt().equals(event.occurredAt())
                || clock.instant().isBefore(proof.confirmedAt())) {
            throw new IllegalStateException("Direct settlement evidence mismatch");
        }

        var state = payments.requestState(org, event.paymentRequestId());
        var request = state.request();
        if (state.operationalStatus() != PaymentOperationalStatus.SETTLED
                || state.attemptInProgress()
                || state.reconciliationRequired()
                || request == null
                || !Objects.equals(request.id(), proof.paymentRequestId())
                || !Objects.equals(request.organizationId(), org)
                || !Objects.equals(request.target(), PaymentTarget.directOrder(directOrderId))
                || !Objects.equals(request.settledPaymentId(), proof.paymentId())
                || request.purpose() != PaymentPurpose.DIRECT_ORDER_INITIAL
                || request.acceptanceStatus() != AcceptanceStatus.CLOSED
                || request.expectedAmount() == null
                || request.expectedAmount().compareTo(proof.amount()) != 0
                || !Objects.equals(request.currency(), proof.currency())) {
            throw new IllegalStateException("Direct settlement has unresolved financial risk");
        }

        List<Order> orders =
                jdbc.query(
                        "select status,currency,accepted_total_xof,payment_confirmed_at from direct_orders"
                                + " where organization_id=? and id=? for update",
                        (rs, row) ->
                                new Order(
                                        rs.getString("status"),
                                        rs.getString("currency"),
                                        rs.getLong("accepted_total_xof"),
                                        rs.getTimestamp("payment_confirmed_at") == null
                                                ? null
                                                : rs.getTimestamp("payment_confirmed_at").toInstant()),
                        org,
                        directOrderId);
        if (orders.isEmpty()) {
            throw new IllegalStateException("Direct settlement order not found");
        }
        Order order = orders.getFirst();
        if ((!"PENDING_PAYMENT_INTEGRATION".equals(order.status())
                        && !"PAYMENT_CONFIRMED".equals(order.status()))
                || !"XOF".equals(order.currency())
                || proof.amount().compareTo(BigDecimal.valueOf(order.total())) != 0) {
            throw new IllegalStateException("Direct settlement order amount/status mismatch");
        }

        List<Receipt> saved =
                jdbc.query(
                        "select payment_request_id,payment_id,amount_xof,currency,provider,"
                                + "provider_transaction_id,confirmed_at"
                                + " from direct_order_verified_settlements"
                                + " where organization_id=? and direct_order_id=?",
                        (rs, row) ->
                                new Receipt(
                                        rs.getObject("payment_request_id", UUID.class),
                                        rs.getObject("payment_id", UUID.class),
                                        rs.getBigDecimal("amount_xof"),
                                        rs.getString("currency"),
                                        rs.getString("provider"),
                                        rs.getString("provider_transaction_id"),
                                        rs.getTimestamp("confirmed_at").toInstant()),
                        org,
                        directOrderId);
        if (!saved.isEmpty()) {
            Receipt existing = saved.getFirst();
            if (!existing.requestId().equals(proof.paymentRequestId())
                    || !existing.paymentId().equals(proof.paymentId())
                    || existing.amount().compareTo(proof.amount()) != 0
                    || !existing.currency().equals(proof.currency())
                    || !existing.provider().equals(proof.provider())
                    || !existing.transactionId().equals(proof.providerTransactionId())
                    || !existing.confirmedAt().equals(proof.confirmedAt())) {
                throw new IllegalStateException("Contradictory direct settlement replay");
            }
        } else {
            if ("PAYMENT_CONFIRMED".equals(order.status())) {
                throw new IllegalStateException("Confirmed direct order has no settlement receipt");
            }
            jdbc.update(
                    "insert into direct_order_verified_settlements"
                            + " (id,event_id,organization_id,direct_order_id,payment_request_id,"
                            + "payment_id,amount_xof,currency,provider,provider_transaction_id,"
                            + "confirmed_at,recorded_at) values (?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(),
                    event.eventId(),
                    org,
                    directOrderId,
                    proof.paymentRequestId(),
                    proof.paymentId(),
                    proof.amount(),
                    proof.currency(),
                    proof.provider(),
                    proof.providerTransactionId(),
                    Timestamp.from(proof.confirmedAt()),
                    Timestamp.from(clock.instant()));
        }

        if ("PAYMENT_CONFIRMED".equals(order.status())) {
            if (!Objects.equals(order.confirmedAt(), proof.confirmedAt())) {
                throw new IllegalStateException("Direct order confirmation timestamp mismatch");
            }
            return;
        }

        int updated =
                jdbc.update(
                        "update direct_orders d set status='PAYMENT_CONFIRMED',"
                                + "payment_confirmed_at=?,version=version+1"
                                + " where d.organization_id=? and d.id=?"
                                + " and d.status='PENDING_PAYMENT_INTEGRATION'"
                                + " and exists (select 1 from direct_order_verified_settlements v"
                                + " where v.organization_id=d.organization_id"
                                + " and v.direct_order_id=d.id and v.payment_request_id=?"
                                + " and v.payment_id=? and v.amount_xof=d.accepted_total_xof"
                                + " and v.currency='XOF' and v.confirmed_at=?)",
                        Timestamp.from(proof.confirmedAt()),
                        org,
                        directOrderId,
                        proof.paymentRequestId(),
                        proof.paymentId(),
                        Timestamp.from(proof.confirmedAt()));
        if (updated != 1) {
            throw new IllegalStateException("Direct financial confirmation could not be committed");
        }
    }
}
