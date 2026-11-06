package sn.groupia.directsales.application;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.payment.api.AcceptanceStatus;
import sn.groupia.payment.api.PaymentOperationalStatus;
import sn.groupia.payment.api.PaymentOperations;
import sn.groupia.payment.api.PaymentPurpose;
import sn.groupia.payment.api.PaymentStatus;
import sn.groupia.payment.api.PaymentTarget;
import sn.groupia.payment.api.dto.PaymentRequestState;
import sn.groupia.payment.api.dto.PaymentSettlementEvidence;
import sn.groupia.shared.api.ApiException;

/**
 * Token-scoped, read-only financial status. This is never permission to collect money or hand over
 * goods. A confirmed payment requires canonical provider evidence and an immutable settlement row.
 */
@Service
@Transactional(readOnly = true)
public class DirectCustomerPaymentStatus {
    public record Status(
            String orderNumber,
            String currency,
            Long acceptedTotalXof,
            String preparationStatus,
            boolean paymentAvailable,
            String financialStatus) {}

    record Snapshot(
            UUID organizationId,
            UUID orderId,
            String orderNumber,
            String orderStatus,
            String currency,
            Long acceptedTotalXof,
            UUID requestId,
            String acceptanceStatus,
            BigDecimal expectedAmount,
            Instant expiresAt,
            UUID receiptPaymentId,
            BigDecimal receiptAmount,
            String receiptProvider,
            String receiptTransaction,
            Instant receiptConfirmedAt,
            Instant orderConfirmedAt) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final PaymentOperations payments;
    private final boolean initiationEnabled;

    // Isolated test fixtures still default to strictly disabled.
    DirectCustomerPaymentStatus(JdbcTemplate jdbc, Clock clock, PaymentOperations payments) {
        this(jdbc, clock, payments, false);
    }

    @Autowired
    public DirectCustomerPaymentStatus(
            JdbcTemplate jdbc,
            Clock clock,
            PaymentOperations payments,
            @Value("${groupia.direct-sales.payment-initiation.enabled:false}")
                    boolean initiationEnabled) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.payments = payments;
        this.initiationEnabled = initiationEnabled;
    }

    public Status lookup(String token) {
        String hash = DirectAccessTokens.hashForLookup(token);
        List<Snapshot> matches =
                jdbc.query(
                        "select d.organization_id,d.id as order_id,d.order_number,d.status as order_status,"
                                + "d.currency,d.accepted_total_xof,d.payment_confirmed_at,"
                                + "p.id as request_id,p.expected_amount,p.acceptance_status,p.expires_at,"
                                + "v.payment_id as receipt_payment_id,v.amount_xof as receipt_amount,"
                                + "v.provider as receipt_provider,"
                                + "v.provider_transaction_id as receipt_transaction,"
                                + "v.confirmed_at as receipt_confirmed_at"
                                + " from direct_orders d"
                                + " left join payment_requests p on p.organization_id=d.organization_id"
                                + " and p.direct_order_id=d.id and p.purpose='DIRECT_ORDER_INITIAL'"
                                + " left join direct_order_verified_settlements v"
                                + " on v.organization_id=d.organization_id and v.direct_order_id=d.id"
                                + " where d.recovery_token_hash=?",
                        (rs, row) ->
                                new Snapshot(
                                        rs.getObject("organization_id", UUID.class),
                                        rs.getObject("order_id", UUID.class),
                                        rs.getString("order_number"),
                                        rs.getString("order_status"),
                                        rs.getString("currency"),
                                        rs.getObject("accepted_total_xof", Long.class),
                                        rs.getObject("request_id", UUID.class),
                                        rs.getString("acceptance_status"),
                                        rs.getBigDecimal("expected_amount"),
                                        instant(rs.getTimestamp("expires_at")),
                                        rs.getObject("receipt_payment_id", UUID.class),
                                        rs.getBigDecimal("receipt_amount"),
                                        rs.getString("receipt_provider"),
                                        rs.getString("receipt_transaction"),
                                        instant(rs.getTimestamp("receipt_confirmed_at")),
                                        instant(rs.getTimestamp("payment_confirmed_at"))),
                        hash);
        if (matches.isEmpty()) {
            throw new ApiException(404, "DIRECT_ORDER_NOT_FOUND", "Commande introuvable.");
        }
        Snapshot saved = matches.getFirst();
        String financial = financialStatus(saved);
        String preparation = preparationStatus(financial);
        // Read-only UI affordance, never permission to pay: the claim transaction independently
        // rechecks every order snapshot, the active capability and financial risk under locks.
        boolean paymentAvailable =
                initiationEnabled
                        && "PREPARED".equals(financial)
                        && "PENDING_PAYMENT_INTEGRATION".equals(saved.orderStatus())
                        && payments.providerAvailable("BICTORYS");
        return new Status(
                saved.orderNumber(),
                saved.currency(),
                saved.acceptedTotalXof(),
                preparation,
                paymentAvailable,
                financial);
    }

    private String financialStatus(Snapshot saved) {
        if (saved.requestId() == null) {
            return "PENDING_PAYMENT_INTEGRATION".equals(saved.orderStatus())
                    ? "NOT_PREPARED"
                    : "UNAVAILABLE";
        }
        if (saved.organizationId() == null
                || saved.orderId() == null
                || saved.acceptedTotalXof() == null
                || saved.acceptedTotalXof() <= 0
                || !"XOF".equals(saved.currency())) {
            return "REVIEW_REQUIRED";
        }

        PaymentRequestState state =
                payments.requestState(saved.organizationId(), saved.requestId());
        if (state == null || state.request() == null) return "REVIEW_REQUIRED";
        var request = state.request();
        BigDecimal total = BigDecimal.valueOf(saved.acceptedTotalXof());
        if (!Objects.equals(request.id(), saved.requestId())
                || !Objects.equals(request.organizationId(), saved.organizationId())
                || !Objects.equals(request.target(), PaymentTarget.directOrder(saved.orderId()))
                || request.purpose() != PaymentPurpose.DIRECT_ORDER_INITIAL
                || request.acceptanceStatus() == null
                || !request.acceptanceStatus().name().equals(saved.acceptanceStatus())
                || !Objects.equals(request.expiresAt(), saved.expiresAt())
                || !"XOF".equals(request.currency())
                || request.expectedAmount() == null
                || request.expectedAmount().compareTo(total) != 0
                || saved.expectedAmount() == null
                || saved.expectedAmount().compareTo(total) != 0
                || state.operationalStatus() == PaymentOperationalStatus.REVIEW_REQUIRED
                || state.reconciliationRequired()) {
            return "REVIEW_REQUIRED";
        }

        if (saved.receiptPaymentId() != null) {
            if (state.operationalStatus() != PaymentOperationalStatus.SETTLED
                    || request.acceptanceStatus() != AcceptanceStatus.CLOSED
                    || !Objects.equals(request.settledPaymentId(), saved.receiptPaymentId())
                    || saved.receiptAmount() == null
                    || saved.receiptAmount().compareTo(total) != 0
                    || saved.receiptConfirmedAt() == null
                    || !Objects.equals(saved.orderConfirmedAt(), saved.receiptConfirmedAt())) {
                return "REVIEW_REQUIRED";
            }
            PaymentSettlementEvidence proof =
                    payments.settlementEvidence(saved.organizationId(), saved.requestId());
            if (proof == null
                    || !Objects.equals(proof.organizationId(), saved.organizationId())
                    || !Objects.equals(proof.paymentRequestId(), saved.requestId())
                    || !Objects.equals(proof.paymentId(), saved.receiptPaymentId())
                    || !Objects.equals(proof.target(), PaymentTarget.directOrder(saved.orderId()))
                    || proof.purpose() != PaymentPurpose.DIRECT_ORDER_INITIAL
                    || proof.acceptanceStatus() != AcceptanceStatus.CLOSED
                    || proof.status() != PaymentStatus.SUCCEEDED
                    || proof.reversal()
                    || !"XOF".equals(proof.currency())
                    || proof.amount() == null
                    || proof.amount().compareTo(total) != 0
                    || proof.provider() == null
                    || proof.provider().isBlank()
                    || proof.providerTransactionId() == null
                    || proof.providerTransactionId().isBlank()
                    || !Objects.equals(proof.provider(), saved.receiptProvider())
                    || !Objects.equals(proof.providerTransactionId(), saved.receiptTransaction())
                    || !Objects.equals(proof.confirmedAt(), saved.receiptConfirmedAt())
                    || clock.instant().isBefore(proof.confirmedAt())
                    || !"PAYMENT_CONFIRMED".equals(saved.orderStatus())
                            && !"PREPARING".equals(saved.orderStatus())
                            && !"READY_FOR_PICKUP".equals(saved.orderStatus())
                            && !"READY_FOR_DISPATCH".equals(saved.orderStatus())
                            && !"DISPATCHED".equals(saved.orderStatus())
                            && !"DELIVERED".equals(saved.orderStatus())
                            && !"PICKED_UP".equals(saved.orderStatus())) {
                return "REVIEW_REQUIRED";
            }
            return "SETTLEMENT_VERIFIED";
        }
        if (!"PENDING_PAYMENT_INTEGRATION".equals(saved.orderStatus())) {
            return "REVIEW_REQUIRED";
        }
        if (state.attemptInProgress()) {
            // A real pending provider attempt is neither paid nor an anomaly by itself.
            // No second payment can be offered while its outcome is unknown.
            return state.operationalStatus() == PaymentOperationalStatus.PENDING
                            && request.acceptanceStatus() == AcceptanceStatus.OPEN
                            && request.settledPaymentId() == null
                    ? "PAYMENT_IN_PROGRESS"
                    : "REVIEW_REQUIRED";
        }
        if (state.operationalStatus() == PaymentOperationalStatus.SETTLED) {
            return "SETTLEMENT_PROCESSING";
        }
        if (state.operationalStatus() == PaymentOperationalStatus.TERMINAL_UNPAID
                || request.acceptanceStatus() != AcceptanceStatus.OPEN) {
            return "UNAVAILABLE";
        }
        if (request.expiresAt() == null || !clock.instant().isBefore(request.expiresAt())) {
            return "EXPIRED";
        }
        return "PREPARED";
    }

    private String preparationStatus(String financial) {
        if ("NOT_PREPARED".equals(financial)) return "NOT_PREPARED";
        if ("PREPARED".equals(financial)) return "PREPARED";
        if ("EXPIRED".equals(financial)) return "EXPIRED";
        return "UNAVAILABLE";
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
