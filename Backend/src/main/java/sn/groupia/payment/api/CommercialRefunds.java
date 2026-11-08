package sn.groupia.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Business callers authorize and hold the order lock before opening cancellation. No transfer is
 * sent.
 */
public interface CommercialRefunds {
    record CaseView(
            UUID id,
            PaymentTarget target,
            String status,
            String currency,
            BigDecimal collectedPrincipal,
            BigDecimal previouslyRefunded,
            BigDecimal amountDue,
            BigDecimal legacyPlatformFee,
            String reason,
            Instant openedAt,
            Instant externallyRecordedAt,
            String externalMethod,
            String maskedReference,
            long version) {}

    CaseView open(
            UUID organizationId,
            PaymentTarget target,
            UUID actor,
            String reason,
            UUID intention,
            long expectedVersion);

    CaseView find(UUID organizationId, PaymentTarget target);

    /**
     * Seller-funded external refunds leave a reimbursement obligation on the original collection
     * account.
     */
    boolean externallyRecorded(UUID organizationId, PaymentTarget target);
}
