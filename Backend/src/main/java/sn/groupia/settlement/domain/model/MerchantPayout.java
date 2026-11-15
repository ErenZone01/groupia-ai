package sn.groupia.settlement.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Exactly one target: a grouping campaign or a direct order. */
public record MerchantPayout(
        UUID id,
        UUID organizationId,
        UUID campaignId,
        BigDecimal amount,
        String currency,
        String status,
        UUID destinationId,
        String method,
        String phoneE164,
        String holderName,
        String provider,
        String providerAccountReference,
        String businessReference,
        UUID providerIdempotencyKey,
        String providerTransactionId,
        UUID requestedBy,
        Instant requestedAt,
        Instant providerOccurredAt,
        Instant succeededAt,
        boolean reconciliationRequired,
        String reconciliationReason,
        long version,
        UUID directOrderId) {

    /** Compatibility constructor for historical campaign payouts and existing tests. */
    public MerchantPayout(
            UUID id,
            UUID organizationId,
            UUID campaignId,
            BigDecimal amount,
            String currency,
            String status,
            UUID destinationId,
            String method,
            String phoneE164,
            String holderName,
            String provider,
            String providerAccountReference,
            String businessReference,
            UUID providerIdempotencyKey,
            String providerTransactionId,
            UUID requestedBy,
            Instant requestedAt,
            Instant providerOccurredAt,
            Instant succeededAt,
            boolean reconciliationRequired,
            String reconciliationReason,
            long version) {
        this(
                id,
                organizationId,
                campaignId,
                amount,
                currency,
                status,
                destinationId,
                method,
                phoneE164,
                holderName,
                provider,
                providerAccountReference,
                businessReference,
                providerIdempotencyKey,
                providerTransactionId,
                requestedBy,
                requestedAt,
                providerOccurredAt,
                succeededAt,
                reconciliationRequired,
                reconciliationReason,
                version,
                null);
    }

    public boolean directSale() {
        return directOrderId != null;
    }

    public boolean reserved() {
        return java.util.Set.of("CREATED", "PENDING", "REVIEW_REQUIRED").contains(status);
    }

    public boolean succeeded() {
        return "SUCCEEDED".equals(status);
    }

    public boolean nonExecuted() {
        return "NON_EXECUTE".equals(status);
    }

    public String maskedPhone() {
        return "+221 ••• •• " + phoneE164.substring(phoneE164.length() - 4);
    }

    @Override
    public String toString() {
        return "MerchantPayout[id=" + id + ", status=" + status + "]";
    }
}
