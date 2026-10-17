package sn.groupia.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Server-only provider primitive. Financial policy and recipient snapshots belong to the caller.
 */
public interface OutgoingPayoutOperations {
    enum Kind {
        REFUND,
        MERCHANT_PAYOUT
    }

    record Registration(
            Kind operationType,
            UUID operationId,
            UUID organizationId,
            String provider,
            String account,
            String businessReference,
            UUID idempotencyKey,
            BigDecimal amount,
            String currency,
            String method,
            Instant createdAt) {}

    record Recipient(String name, String phone) {
        @Override
        public String toString() {
            return "PayoutRecipient[redacted]";
        }
    }

    record Evidence(
            UUID receiptId,
            Kind operationType,
            UUID operationId,
            String provider,
            String account,
            String businessReference,
            String transactionId,
            BigDecimal amount,
            String currency,
            String method,
            String recipientPhone,
            String status,
            Instant occurredAt,
            BigDecimal providerFee,
            boolean authenticated,
            String errorCode) {
        @Override
        public String toString() {
            return "OutgoingPayoutEvidence[receiptId=" + receiptId + "]";
        }
    }

    boolean available();

    void register(Registration operation);

    String bind(Kind kind, UUID id, String transactionId);

    String initiate(Kind kind, UUID id, Recipient recipient);

    Evidence verify(Kind kind, UUID id);

    Evidence readEvidence(UUID receiptId);

    void acknowledgeEvidence(UUID receiptId, String errorCode);

    void incident(Kind kind, UUID id, String errorCode);
}
