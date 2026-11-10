package sn.groupia.payment.domain.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.domain.service.*;

@Entity
@Table(name = "payments")
public class Payment {
    @Id private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false, updatable = false)
    private UUID paymentRequestId;

    @Column(nullable = false, length = 64, updatable = false)
    private String provider;

    @Column(length = 128, updatable = false)
    private String providerAccountReference;

    @Column(nullable = false, length = 80, updatable = false)
    private String merchantReference;

    @Column(length = 160)
    private String providerTransactionId;

    @Column(nullable = false, length = 128, updatable = false)
    private String providerIdempotencyKey;

    @Column(nullable = false, length = 128, updatable = false)
    private String initiationKey;

    @Column(nullable = false, updatable = false, columnDefinition = "numeric")
    private BigDecimal expectedAmount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(columnDefinition = "numeric")
    private BigDecimal confirmedAmount;

    @Column(length = 3)
    private String confirmedCurrency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentStatus status;

    @Column(nullable = false)
    private boolean reconciliationRequired;

    @Column(length = 64)
    private String reconciliationReason;

    @Column(nullable = false, updatable = false)
    private Instant initiatedAt;

    private Instant providerOccurredAt;
    private Instant confirmedAt;
    private Instant lastCheckedAt;

    @Column(length = 40, updatable = false)
    private String paymentReference;

    @Column(length = 160)
    private String providerChargeId;

    // Canonical exact monetary evidence from a verified PSP financial transaction.
    @Column(columnDefinition = "numeric")
    private BigDecimal providerCollectionFee;

    @Column(columnDefinition = "numeric")
    private BigDecimal providerCustomerCharged;

    @Column(length = 4096)
    private String checkoutUrl;

    @Column(updatable = false)
    private UUID feeQuoteId;

    public UUID getFeeQuoteId() {
        return feeQuoteId;
    }

    public BigDecimal getProviderCollectionFee() {
        return providerCollectionFee;
    }

    public BigDecimal getProviderCustomerCharged() {
        return providerCustomerCharged;
    }

    @Version private Long version;

    protected Payment() {}

    public static Payment create(
            PaymentRequest r, String provider, String account, String key, Instant now) {
        var p = new Payment();
        p.id = UUID.randomUUID();
        p.organizationId = r.getOrganizationId();
        p.paymentRequestId = r.getId();
        p.provider = provider;
        p.providerAccountReference = account;
        p.merchantReference = "GP-" + UUID.randomUUID();
        p.providerIdempotencyKey = UUID.randomUUID().toString();
        p.initiationKey = key;
        p.expectedAmount = r.getExpectedAmount();
        p.currency = r.getCurrency();
        p.status = PaymentStatus.CREATED;
        p.initiatedAt = now;
        p.review("PROVIDER_STATE_UNCERTAIN");
        return p;
    }

    public void checkoutContext(PaymentCheckoutContext context) {
        if (context != null) {
            paymentReference = context.businessReference();
            feeQuoteId = context.feeQuoteId();
        }
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public void checkoutDetails(ProviderMetadata details) {
        if (details != null) {
            if (details.chargeId() != null) {
                providerChargeId = details.chargeId();
            }
            if (details.checkoutUrl() != null) {
                checkoutUrl = details.checkoutUrl();
            }
        }
    }

    public void review(String reason) {
        reconciliationRequired = true;
        if (!"PROVIDER_REVERSAL".equals(reconciliationReason)) {
            reconciliationReason = reason;
        }
    }

    public void retainVerifiedTransaction(ProviderObservation observation) {
        if (providerTransactionId == null) {
            providerTransactionId = observation.providerTransactionId();
        }
    }

    public void checked(Instant now) {
        lastCheckedAt = now;
    }

    public void clearDuplicateReview() {
        if (PaymentReview.DUPLICATE.equals(reconciliationReason)) {
            reconciliationRequired = false;
            reconciliationReason = null;
        }
    }

    public void markDuplicateReview() {
        if (reconciliationReason == null
                || PaymentReview.DUPLICATE.equals(reconciliationReason)
                || "DUPLICATE_SUCCESS".equals(reconciliationReason)
                || "PROVIDER_STATE_UNCERTAIN".equals(reconciliationReason)) {
            review(PaymentReview.DUPLICATE);
        }
    }

    public boolean preventsNewAttempt() {
        return reconciliationRequired
                || status == PaymentStatus.CREATED
                || status == PaymentStatus.PENDING
                || status == PaymentStatus.SUCCEEDED;
    }

    public boolean apply(ProviderObservation o, Instant now) {
        lastCheckedAt = now;
        if (status == PaymentStatus.SUCCEEDED) {
            return false;
        }
        if (o.status() != PaymentStatus.SUCCEEDED
                && providerOccurredAt != null
                && o.occurredAt() != null
                && o.occurredAt().isBefore(providerOccurredAt)) {
            return false;
        }
        if (o.status() == PaymentStatus.CREATED) {
            review("PROVIDER_STATE_UNCERTAIN");
            return false;
        }
        if ((status == PaymentStatus.FAILED
                        || status == PaymentStatus.CANCELLED
                        || status == PaymentStatus.EXPIRED)
                && o.status() != PaymentStatus.SUCCEEDED) {
            return false;
        }
        providerTransactionId = o.providerTransactionId();
        providerOccurredAt = o.occurredAt();
        status = o.status();
        reconciliationRequired = false;
        reconciliationReason = null;
        if (status == PaymentStatus.SUCCEEDED) {
            if (o.metadata() != null
                    && o.metadata().collectionFee() != null
                    && o.metadata().customerCharged() != null) {
                providerCollectionFee = o.metadata().collectionFee();
                providerCustomerCharged = o.metadata().customerCharged();
            }
            confirmedAmount = expectedAmount;
            confirmedCurrency = currency;
            // PostgreSQL TIMESTAMPTZ persists at microsecond precision. Modulith publishes
            // the confirmation timestamp before a later consumer reloads this row; preserve
            // the exact same canonical instant across both sides of that transaction.
            confirmedAt = now.truncatedTo(ChronoUnit.MICROS);
            return true;
        }
        return false;
    }

    public ProviderInstruction instruction(PaymentRequest r) {
        return new ProviderInstruction(
                id,
                paymentRequestId,
                r.getOrderId(),
                r.getPurpose(),
                provider,
                providerAccountReference,
                merchantReference,
                providerTransactionId,
                providerIdempotencyKey,
                expectedAmount,
                currency,
                paymentReference == null
                        ? null
                        : new PaymentCheckoutContext(
                                paymentReference, null, null, null, feeQuoteId),
                r.getTarget(),
                "SASPAY".equals(provider) ? providerChargeId : null);
    }

    public PaymentAttemptView view() {
        return new PaymentAttemptView(
                id,
                status,
                expectedAmount,
                currency,
                reconciliationRequired,
                initiatedAt,
                status == PaymentStatus.PENDING && !reconciliationRequired ? checkoutUrl : null);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getPaymentRequestId() {
        return paymentRequestId;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderAccountReference() {
        return providerAccountReference;
    }

    public String getMerchantReference() {
        return merchantReference;
    }

    public String getProviderTransactionId() {
        return providerTransactionId;
    }

    public String getProviderIdempotencyKey() {
        return providerIdempotencyKey;
    }

    public String getInitiationKey() {
        return initiationKey;
    }

    public BigDecimal getExpectedAmount() {
        return expectedAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getConfirmedAmount() {
        return confirmedAmount;
    }

    public String getConfirmedCurrency() {
        return confirmedCurrency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public boolean isReconciliationRequired() {
        return reconciliationRequired;
    }

    public String getReconciliationReason() {
        return reconciliationReason;
    }

    public Instant getInitiatedAt() {
        return initiatedAt;
    }

    public Instant getProviderOccurredAt() {
        return providerOccurredAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public Long getVersion() {
        return version;
    }
}
