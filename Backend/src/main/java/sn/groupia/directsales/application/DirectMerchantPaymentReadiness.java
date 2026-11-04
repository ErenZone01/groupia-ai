package sn.groupia.directsales.application;

import java.math.BigDecimal;
import java.time.Clock;
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
import sn.groupia.payment.api.dto.PaymentRequestView;
import sn.groupia.payment.api.dto.PaymentSettlementEvidence;
import sn.groupia.shared.api.ApiException;

/** Merchant-scoped financial read model; never grants shipping, pickup or payout authority. */
@Service
@Transactional(readOnly = true)
public class DirectMerchantPaymentReadiness {
    record Snapshot(
            String number,
            String currency,
            Long acceptedTotalXof,
            UUID requestId,
            UUID receiptPaymentId,
            BigDecimal receiptAmount) {}

    public record Status(
            String orderNumber,
            Long acceptedTotalXof,
            String financialStatus,
            boolean fulfillmentAllowed) {}

    private final JdbcTemplate jdbc;
    private final OrganizationAccess access;
    private final PaymentOperations payments;
    private final Clock clock;

    public DirectMerchantPaymentReadiness(
            JdbcTemplate jdbc, OrganizationAccess access, PaymentOperations payments, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.payments = payments;
        this.clock = clock;
    }

    public Status get(UUID organizationId, UUID orderId) {
        access.requirePermission(organizationId, OrganizationPermission.VIEW_ORDERS);
        List<Snapshot> rows =
                jdbc.query(
                        "select d.order_number,d.currency,d.accepted_total_xof,"
                                + "p.id as request_id,v.payment_id as receipt_payment_id,"
                                + "v.amount_xof as receipt_amount"
                                + " from direct_orders d"
                                + " left join payment_requests p on p.organization_id=d.organization_id"
                                + " and p.direct_order_id=d.id and p.purpose='DIRECT_ORDER_INITIAL'"
                                + " left join direct_order_verified_settlements v"
                                + " on v.organization_id=d.organization_id and v.direct_order_id=d.id"
                                + " where d.organization_id=? and d.id=?",
                        (rs, row) ->
                                new Snapshot(
                                        rs.getString("order_number"),
                                        rs.getString("currency"),
                                        rs.getObject("accepted_total_xof", Long.class),
                                        rs.getObject("request_id", UUID.class),
                                        rs.getObject("receipt_payment_id", UUID.class),
                                        rs.getBigDecimal("receipt_amount")),
                        organizationId,
                        orderId);
        if (rows.isEmpty()) {
            throw new ApiException(404, "DIRECT_ORDER_NOT_FOUND", "Commande introuvable.");
        }
        Snapshot saved = rows.getFirst();
        if (saved.requestId() == null) {
            return status(saved, "NOT_PREPARED");
        }
        var state = payments.requestState(organizationId, saved.requestId());
        PaymentRequestView request = state.request();
        BigDecimal accepted =
                saved.acceptedTotalXof() == null
                        ? null
                        : BigDecimal.valueOf(saved.acceptedTotalXof());
        boolean consistent =
                request != null
                        && Objects.equals(request.id(), saved.requestId())
                        && Objects.equals(request.organizationId(), organizationId)
                        && Objects.equals(request.target(), PaymentTarget.directOrder(orderId))
                        && request.purpose() == PaymentPurpose.DIRECT_ORDER_INITIAL
                        && accepted != null
                        && accepted.signum() > 0
                        && request.expectedAmount() != null
                        && request.expectedAmount().compareTo(accepted) == 0
                        && "XOF".equals(saved.currency())
                        && "XOF".equals(request.currency());
        if (!consistent
                || state.operationalStatus() == PaymentOperationalStatus.REVIEW_REQUIRED
                || state.reconciliationRequired()) {
            return status(saved, "REVIEW_REQUIRED");
        }
        if (saved.receiptPaymentId() != null) {
            if (state.operationalStatus() != PaymentOperationalStatus.SETTLED
                    || !Objects.equals(request.settledPaymentId(), saved.receiptPaymentId())) {
                return status(saved, "REVIEW_REQUIRED");
            }
            PaymentSettlementEvidence proof =
                    payments.settlementEvidence(organizationId, saved.requestId());
            boolean verified =
                    Objects.equals(proof.organizationId(), organizationId)
                            && Objects.equals(proof.paymentRequestId(), saved.requestId())
                            && Objects.equals(proof.paymentId(), saved.receiptPaymentId())
                            && Objects.equals(proof.target(), PaymentTarget.directOrder(orderId))
                            && proof.purpose() == PaymentPurpose.DIRECT_ORDER_INITIAL
                            && proof.acceptanceStatus() == AcceptanceStatus.CLOSED
                            && proof.status() == PaymentStatus.SUCCEEDED
                            && !proof.reversal()
                            && "XOF".equals(proof.currency())
                            && proof.amount() != null
                            && proof.amount().compareTo(accepted) == 0
                            && saved.receiptAmount() != null
                            && saved.receiptAmount().compareTo(accepted) == 0;
            return status(saved, verified ? "SETTLEMENT_VERIFIED" : "REVIEW_REQUIRED");
        }
        if (state.attemptInProgress()) {
            // Merchant can track an initiated checkout but must neither re-charge nor
            // release merchandise before immutable provider settlement is verified.
            return status(
                    saved,
                    state.operationalStatus() == PaymentOperationalStatus.PENDING
                                    && request.acceptanceStatus() == AcceptanceStatus.OPEN
                                    && request.settledPaymentId() == null
                            ? "PAYMENT_IN_PROGRESS"
                            : "REVIEW_REQUIRED");
        }
        if (state.operationalStatus() == PaymentOperationalStatus.SETTLED) {
            return status(saved, "SETTLEMENT_PROCESSING");
        }
        if (state.operationalStatus() == PaymentOperationalStatus.TERMINAL_UNPAID
                || request.acceptanceStatus() != AcceptanceStatus.OPEN) {
            return status(saved, "UNAVAILABLE");
        }
        if (request.expiresAt() == null || !clock.instant().isBefore(request.expiresAt())) {
            return status(saved, "EXPIRED");
        }
        return status(saved, "PREPARED");
    }

    private static Status status(Snapshot saved, String name) {
        return new Status(saved.number(), saved.acceptedTotalXof(), name, false);
    }
}
