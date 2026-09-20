package sn.groupia.payment.domain.model;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import jakarta.persistence.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.domain.service.*;
import sn.groupia.shared.api.ApiException;

@Entity @Table(name = "payment_requests")
public class PaymentRequest {
    @Id private UUID id;
    @Column(nullable = false, updatable = false) private UUID organizationId;
    @Column(nullable = false, updatable = false) private UUID orderId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32, updatable = false) private PaymentPurpose purpose;
    @Column(nullable = false, length = 160, updatable = false) private String businessReference;
    @Column(nullable = false, updatable = false, columnDefinition = "numeric") private BigDecimal expectedAmount;
    @Column(nullable = false, updatable = false, columnDefinition = "numeric") private BigDecimal goodsAmount;
    @Column(nullable = false, updatable = false, columnDefinition = "numeric") private BigDecimal feeAmount;
    @Column(nullable = false, updatable = false, columnDefinition = "numeric") private BigDecimal shippingAmount;
    @Column(nullable = false, length = 3, updatable = false) private String currency;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private AcceptanceStatus acceptanceStatus;
     private UUID settledPaymentId;
    @Column(nullable = false, updatable = false) private Instant expiresAt;
    @Column(nullable = false, updatable = false) private Instant createdAt;
     private Instant closedAt;
     private Instant cancelledAt;
    @Version private Long version;
    protected PaymentRequest() {}

    public static PaymentRequest create(CreatePaymentRequest c, Instant now) {
        if (c.organizationId() == null || c.orderId() == null || c.purpose() == null || c.expiresAt() == null
                || c.businessReference() == null || !c.businessReference().matches("[A-Za-z0-9:._-]{1,160}")
                || !"XOF".equals(c.currency())) { throw new ApiException(400,"INVALID_PAYMENT_REQUEST","Obligation invalide."); }
        var r = new PaymentRequest(); r.id = UUID.randomUUID(); r.organizationId = c.organizationId(); r.orderId = c.orderId();
        r.purpose = c.purpose(); r.businessReference = c.businessReference(); r.currency = c.currency();
        r.expectedAmount = FinancialValues.money(c.expectedAmount()); r.goodsAmount = FinancialValues.money(c.goodsAmount());
        r.feeAmount = FinancialValues.money(c.feeAmount()); r.shippingAmount = FinancialValues.money(c.shippingAmount());
        if (r.goodsAmount.add(r.feeAmount).add(r.shippingAmount).compareTo(r.expectedAmount) != 0) {
            throw new ApiException(400,"INVALID_PAYMENT_BREAKDOWN","Ventilation incohérente.");
        }
        r.expiresAt = c.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS); r.createdAt = now; r.acceptanceStatus = AcceptanceStatus.OPEN;
        return r;
    }
    public boolean matches(CreatePaymentRequest c) {
        return organizationId.equals(c.organizationId()) && orderId.equals(c.orderId()) && purpose == c.purpose()
                && currency.equals(c.currency()) && expectedAmount.compareTo(c.expectedAmount()) == 0
                && goodsAmount.compareTo(c.goodsAmount()) == 0 && feeAmount.compareTo(c.feeAmount()) == 0
                && shippingAmount.compareTo(c.shippingAmount()) == 0
                && expiresAt.equals(c.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
    public void requireOpen(Instant now) {
        if (settledPaymentId != null) { throw new ApiException(409,"PAYMENT_ALREADY_SETTLED","Cette obligation est réglée."); }
        if (!now.isBefore(expiresAt)) { throw new ApiException(409,"PAYMENT_REQUEST_EXPIRED","Le délai de paiement est dépassé."); }
        if (acceptanceStatus != AcceptanceStatus.OPEN) { throw new ApiException(409,"PAYMENT_REQUEST_CLOSED","Cette obligation est fermée."); }
        if (expectedAmount.signum() == 0) { throw new ApiException(409,"PAYMENT_ZERO_AMOUNT_REQUIRES_REVIEW","Une obligation nulle nécessite une décision explicite."); }
    }
    /** Returns the reason for preserving a real success without allocating it. */
    public String allocate(UUID paymentId, Instant now) {
        if (settledPaymentId != null) { return settledPaymentId.equals(paymentId) ? null : "DUPLICATE_SUCCESS"; }
        if (!now.isBefore(expiresAt) || acceptanceStatus != AcceptanceStatus.OPEN) { return "LATE_SUCCESS"; }
        settledPaymentId = paymentId; acceptanceStatus = AcceptanceStatus.CLOSED; closedAt = now;
        return null;
    }
    public boolean cancel(Instant now) {
        if (settledPaymentId != null) { return false; }
        if (acceptanceStatus == AcceptanceStatus.OPEN) { acceptanceStatus = AcceptanceStatus.CANCELLED; cancelledAt = now; }
        return true;
    }
    public PaymentRequestView view() {
        return new PaymentRequestView(id,organizationId,orderId,purpose,businessReference,expectedAmount,currency,
                goodsAmount,feeAmount,shippingAmount,acceptanceStatus,expiresAt,settledPaymentId,createdAt,closedAt,cancelledAt,version);
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getOrderId() { return orderId; }
    public PaymentPurpose getPurpose() { return purpose; }
    public String getBusinessReference() { return businessReference; }
    public BigDecimal getExpectedAmount() { return expectedAmount; }
    public BigDecimal getGoodsAmount() { return goodsAmount; }
    public BigDecimal getFeeAmount() { return feeAmount; }
    public BigDecimal getShippingAmount() { return shippingAmount; }
    public String getCurrency() { return currency; }
    public AcceptanceStatus getAcceptanceStatus() { return acceptanceStatus; }
    public UUID getSettledPaymentId() { return settledPaymentId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClosedAt() { return closedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Long getVersion() { return version; }
}
