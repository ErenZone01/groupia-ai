package sn.groupia.settlement.domain.service;

import java.math.BigDecimal;
import java.time.Instant;
import sn.groupia.settlement.domain.model.MerchantPayout;

/**
 * Initiation is never proof. Implementations must authenticate server evidence before returning
 * Proof. No browser endpoint accepts a Proof. Settlement remains independent of provider wire
 * formats.
 */
public interface MerchantPayoutProvider {
    boolean enabled();

    String name();

    String account();

    String initiate(MerchantPayout payout);

    Proof verify(MerchantPayout payout);

    default boolean publishesEvidence() {
        return false;
    }

    record Proof(
            boolean authenticated,
            String provider,
            String account,
            String businessReference,
            String transactionId,
            BigDecimal amount,
            String currency,
            String method,
            String phoneE164,
            String status,
            Instant occurredAt) {
        @Override
        public String toString() {
            return "MerchantPayoutProof[redacted]";
        }
    }
}
