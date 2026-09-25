package sn.groupia.payment.domain.model;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import jakarta.persistence.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.domain.service.*;
import sn.groupia.shared.api.ApiException;

@Entity @Table(name = "payment_webhook_events")
public class PaymentWebhookEvent {
    @Id private UUID id;
    @Column(nullable = false, length = 64) private String provider;
    @Column(length = 128) private String providerAccountReference;
    @Column(length = 160) private String providerEventId;
    @Column(nullable = false, length = 64) private String deduplicationKey;
     private UUID organizationId;
     private UUID paymentId;
    @Column(nullable = false, length = 16) private String authenticityVerificationResult;
    @Column(nullable = false, length = 64) private String payloadFingerprint;
    @Column(nullable = false, length = 32) private String processingStatus;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON) @Column(columnDefinition = "jsonb") private ProviderObservation observation;
    @Column(nullable = false) private Instant receivedAt;
     private Instant processedAt;
    @Column(nullable = false) private int attemptCount;
     private Instant nextAttemptAt;
    @Column(length = 64) private String lastErrorCode;
    @Version private Long version;
    protected PaymentWebhookEvent() {}

    public static PaymentWebhookEvent receive(String provider, String account, String eventId, String key,
            String fingerprint, boolean authentic, ProviderObservation observation, Instant now) {
        var e = new PaymentWebhookEvent(); e.id = UUID.randomUUID(); e.provider = provider; e.providerAccountReference = account;
        e.providerEventId = eventId; e.deduplicationKey = key; e.payloadFingerprint = fingerprint;
        e.authenticityVerificationResult = authentic ? "VALID" : "INVALID"; e.observation = observation; e.receivedAt = now;
        e.processingStatus = authentic ? "RECEIVED" : "IGNORED";
        if (!authentic) { e.processedAt = now; e.lastErrorCode = "PAYMENT_AUTHENTICITY_FAILED"; }
        return e;
    }
    public void resolve(Payment p) { paymentId = p.getId(); organizationId = p.getOrganizationId(); }
    public void parsingFailed(String code, Instant now) {
        // A redelivery can clarify a legacy null-observation UNKNOWN_REFERENCE receipt.
        // Never rewrite a receipt that already carries financial evidence or an explicit diagnosis.
        if ("VALID".equals(authenticityVerificationResult) && observation==null && paymentId==null
                && (pending() || ("REVIEW_REQUIRED".equals(processingStatus) && "UNKNOWN_REFERENCE".equals(lastErrorCode)))) {
            finish("REVIEW_REQUIRED",code,now);
        }
    }
    public void finish(String status, String error, Instant now) {
        attemptCount++; processingStatus = status; lastErrorCode = error;
        nextAttemptAt = null; processedAt = now;
    }
    public void retry(String error, Instant now) {
        attemptCount++; lastErrorCode = error;
        processingStatus = attemptCount >= 5 ? "REVIEW_REQUIRED" : "RETRYABLE_FAILURE";
        nextAttemptAt = attemptCount >= 5 ? null : now.plusSeconds(60L * attemptCount);
        if(attemptCount>=5){processedAt=now;}
    }
    public boolean pending() { return processingStatus.equals("RECEIVED") || processingStatus.equals("RETRYABLE_FAILURE"); }

    public UUID getId() { return id; }
    public String getProvider() { return provider; }
    public String getProviderAccountReference() { return providerAccountReference; }
    public String getProviderEventId() { return providerEventId; }
    public String getDeduplicationKey() { return deduplicationKey; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getPaymentId() { return paymentId; }
    public String getAuthenticityVerificationResult() { return authenticityVerificationResult; }
    public String getPayloadFingerprint() { return payloadFingerprint; }
    public String getProcessingStatus() { return processingStatus; }
    public ProviderObservation getObservation() { return observation; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastErrorCode() { return lastErrorCode; }
    public Long getVersion() { return version; }
}
