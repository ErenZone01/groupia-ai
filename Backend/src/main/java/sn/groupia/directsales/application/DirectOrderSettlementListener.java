package sn.groupia.directsales.application;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import sn.groupia.payment.api.AcceptanceStatus;
import sn.groupia.payment.api.PaymentOperations;
import sn.groupia.payment.api.PaymentPurpose;
import sn.groupia.payment.api.PaymentStatus;
import sn.groupia.payment.api.PaymentTargetType;
import sn.groupia.payment.api.dto.PaymentSettlementEvidence;
import sn.groupia.payment.api.event.PaymentObligationSettled;

/**
 * Records authoritative successful payment evidence without changing direct-order fulfillment
 * status. Replayed Modulith events are safe; contradictory evidence is never silently accepted.
 */
@Component
public class DirectOrderSettlementListener {
    private record Order(String status, String currency, long total) {}

    private record Receipt(
            UUID requestId,
            UUID paymentId,
            BigDecimal amount,
            String provider,
            String transactionId) {}

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

        List<Order> orders =
                jdbc.query(
                        "select status,currency,accepted_total_xof from direct_orders"
                                + " where organization_id=? and id=? for update",
                        (rs, row) ->
                                new Order(
                                        rs.getString("status"),
                                        rs.getString("currency"),
                                        rs.getLong("accepted_total_xof")),
                        org,
                        directOrderId);
        if (orders.isEmpty()) {
            throw new IllegalStateException("Direct settlement order not found");
        }
        Order order = orders.getFirst();
        if (!"PENDING_PAYMENT_INTEGRATION".equals(order.status())
                || !"XOF".equals(order.currency())
                || proof.amount().compareTo(BigDecimal.valueOf(order.total())) != 0) {
            throw new IllegalStateException("Direct settlement order amount/status mismatch");
        }

        List<Receipt> saved =
                jdbc.query(
                        "select payment_request_id,payment_id,amount_xof,provider,"
                                + "provider_transaction_id"
                                + " from direct_order_verified_settlements"
                                + " where organization_id=? and direct_order_id=?",
                        (rs, row) ->
                                new Receipt(
                                        rs.getObject("payment_request_id", UUID.class),
                                        rs.getObject("payment_id", UUID.class),
                                        rs.getBigDecimal("amount_xof"),
                                        rs.getString("provider"),
                                        rs.getString("provider_transaction_id")),
                        org,
                        directOrderId);
        if (!saved.isEmpty()) {
            Receipt existing = saved.getFirst();
            if (!existing.requestId().equals(proof.paymentRequestId())
                    || !existing.paymentId().equals(proof.paymentId())
                    || existing.amount().compareTo(proof.amount()) != 0
                    || !existing.provider().equals(proof.provider())
                    || !existing.transactionId().equals(proof.providerTransactionId())) {
                throw new IllegalStateException("Contradictory direct settlement replay");
            }
            return;
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
}
