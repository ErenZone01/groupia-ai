package sn.groupia.payment.domain.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.domain.service.*;
import sn.groupia.shared.api.ApiException;

@Entity
@Table(name = "payment_requests")
public class PaymentRequest {
    @Id private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID organizationId;

    @Column(updatable = false)
    private UUID orderId;

    @Column(updatable = false)
    private UUID directOrderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentTargetType targetType;

    @Column(nullable = false, updatable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private PaymentPurpose purpose;

    @Column(nullable = false, length = 160, updatable = false)
    private String businessReference;

    @Column(nullable = false, updatable = false, columnDefinition = "numeric")
    private BigDecimal expectedAmount;

    @Column(nullable = false, updatable = false, columnDefinition = "numeric")
    private BigDecimal goodsAmount;

    @Column(nullable = false, updatable = false, columnDefinition = "numeric")
    private BigDecimal feeAmount;

    @Column(nullable = false, updatable = false, columnDefinition = "numeric")
    private BigDecimal shippingAmount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AcceptanceStatus acceptanceStatus;

    private UUID settledPaymentId;

    @Column(updatable = false)
    private Instant expiresAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant closedAt;
    private Instant cancelledAt;
    @Version private Long version;

    protected PaymentRequest() {}

    public static PaymentRequest create(CreatePaymentRequest c, Instant now) {
        if (c.organizationId() == null
                || c.target() == null
                || c.purpose() == null
                || (c.purpose() != PaymentPurpose.SHIPPING_BALANCE && c.expiresAt() == null)
                || c.businessReference() == null
                || !c.businessReference().matches("[A-Za-z0-9:._-]{1,160}")
                || !"XOF".equals(c.currency())) {
            throw new ApiException(400, "INVALID_PAYMENT_REQUEST", "Obligation invalide.");
        }
        boolean targetCompatible =
                switch (c.purpose()) {
                    case INITIAL_ORDER, SHIPPING_BALANCE ->
                            c.target().type() == PaymentTargetType.ORDER;
                    case SUBSCRIPTION_MONTHLY ->
                            c.target().type() == PaymentTargetType.SUBSCRIPTION_INVOICE;
                    case DIRECT_ORDER_INITIAL ->
                            c.target().type() == PaymentTargetType.DIRECT_ORDER;
                };
        if (!targetCompatible) {
            throw new ApiException(
                    400, "INVALID_PAYMENT_TARGET", "Cible incompatible avec l'obligation.");
        }
        var r = new PaymentRequest();
        r.id = UUID.randomUUID();
        r.organizationId = c.organizationId();
        r.orderId = c.orderId();
        r.directOrderId =
                c.target().type() == PaymentTargetType.DIRECT_ORDER ? c.target().id() : null;
        r.targetType = c.target().type();
        r.targetId = c.target().id();
        r.purpose = c.purpose();
        r.businessReference = c.businessReference();
        r.currency = c.currency();
        r.expectedAmount = FinancialValues.money(c.expectedAmount());
        r.goodsAmount = FinancialValues.money(c.goodsAmount());
        r.feeAmount = FinancialValues.money(c.feeAmount());
        r.shippingAmount = FinancialValues.money(c.shippingAmount());
        if ((r.targetType == PaymentTargetType.ORDER
                        || r.targetType == PaymentTargetType.DIRECT_ORDER)
                && r.goodsAmount.add(r.feeAmount).add(r.shippingAmount).compareTo(r.expectedAmount)
                        != 0) {
            throw new ApiException(400, "INVALID_PAYMENT_BREAKDOWN", "Ventilation incohérente.");
        }
        if (r.purpose == PaymentPurpose.DIRECT_ORDER_INITIAL
                && (r.expectedAmount.signum() <= 0
                        || r.expectedAmount.compareTo(new BigDecimal("99999999999999")) > 0)) {
            throw new ApiException(
                    400, "INVALID_PAYMENT_BREAKDOWN", "Total de vente directe invalide.");
        }
        if (r.purpose == PaymentPurpose.SHIPPING_BALANCE
                && (r.expectedAmount.signum() <= 0
                        || r.goodsAmount.signum() != 0
                        || r.feeAmount.signum() != 0)) {
            throw new ApiException(
                    400,
                    "INVALID_PAYMENT_BREAKDOWN",
                    "Le solde transport doit être positif, sans frais ni marchandises.");
        }
        if (r.purpose == PaymentPurpose.SUBSCRIPTION_MONTHLY
                && (r.expectedAmount.signum() <= 0
                        || r.goodsAmount.signum() != 0
                        || r.feeAmount.signum() != 0
                        || r.shippingAmount.signum() != 0)) {
            throw new ApiException(
                    400, "INVALID_PAYMENT_BREAKDOWN", "Montant d'abonnement invalide.");
        }
        r.expiresAt = normalize(c.expiresAt());
        r.createdAt = now;
        r.acceptanceStatus = AcceptanceStatus.OPEN;
        return r;
    }

    private static Instant normalize(Instant value) {
        return value == null ? null : value.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    public boolean matches(CreatePaymentRequest c) {
        return organizationId.equals(c.organizationId())
                && getTarget().equals(c.target())
                && purpose == c.purpose()
                && currency.equals(c.currency())
                && expectedAmount.compareTo(c.expectedAmount()) == 0
                && goodsAmount.compareTo(c.goodsAmount()) == 0
                && feeAmount.compareTo(c.feeAmount()) == 0
                && shippingAmount.compareTo(c.shippingAmount()) == 0
                && Objects.equals(expiresAt, normalize(c.expiresAt()));
    }

    public void requireOpen(Instant now) {
        if (settledPaymentId != null) {
            throw new ApiException(409, "PAYMENT_ALREADY_SETTLED", "Cette obligation est réglée.");
        }
        if ((expiresAt != null && !now.isBefore(expiresAt))) {
            throw new ApiException(
                    409, "PAYMENT_REQUEST_EXPIRED", "Le délai de paiement est dépassé.");
        }
        if (acceptanceStatus != AcceptanceStatus.OPEN) {
            throw new ApiException(409, "PAYMENT_REQUEST_CLOSED", "Cette obligation est fermée.");
        }
        if (expectedAmount.signum() == 0) {
            throw new ApiException(
                    409,
                    "PAYMENT_ZERO_AMOUNT_REQUIRES_REVIEW",
                    "Une obligation nulle nécessite une décision explicite.");
        }
    }

    /** Returns the reason for preserving a real success without allocating it. */
    public String allocate(UUID paymentId, Instant now) {
        String rejection = settlementRejection(paymentId, now);
        if (rejection != null) {
            return rejection;
        }
        if (settledPaymentId != null) {
            return null;
        }
        settledPaymentId = paymentId;
        acceptanceStatus = AcceptanceStatus.CLOSED;
        closedAt = now;
        return null;
    }

    /** Read-only eligibility check, shared by allocation and authoritative webhook validation. */
    public String settlementRejection(UUID paymentId, Instant now) {
        if (settledPaymentId != null) {
            return settledPaymentId.equals(paymentId) ? null : "DUPLICATE_SUCCESS";
        }
        if ((expiresAt != null && !now.isBefore(expiresAt))
                || acceptanceStatus != AcceptanceStatus.OPEN) {
            return "LATE_SUCCESS";
        }
        return null;
    }

    public boolean cancel(Instant now) {
        if (settledPaymentId != null) {
            return false;
        }
        if (acceptanceStatus == AcceptanceStatus.OPEN) {
            acceptanceStatus = AcceptanceStatus.CANCELLED;
            cancelledAt = now;
        }
        return true;
    }

    public PaymentRequestView view() {
        return new PaymentRequestView(
                id,
                organizationId,
                orderId,
                purpose,
                businessReference,
                expectedAmount,
                currency,
                goodsAmount,
                feeAmount,
                shippingAmount,
                acceptanceStatus,
                expiresAt,
                settledPaymentId,
                createdAt,
                closedAt,
                cancelledAt,
                version,
                getTarget());
    }

    public PaymentTarget getTarget() {
        return new PaymentTarget(targetType, targetId);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public PaymentPurpose getPurpose() {
        return purpose;
    }

    public String getBusinessReference() {
        return businessReference;
    }

    public BigDecimal getExpectedAmount() {
        return expectedAmount;
    }

    public BigDecimal getGoodsAmount() {
        return goodsAmount;
    }

    public BigDecimal getFeeAmount() {
        return feeAmount;
    }

    public BigDecimal getShippingAmount() {
        return shippingAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public AcceptanceStatus getAcceptanceStatus() {
        return acceptanceStatus;
    }

    public UUID getSettledPaymentId() {
        return settledPaymentId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Long getVersion() {
        return version;
    }
}
