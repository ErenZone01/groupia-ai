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

    /** One persisted collection account funds this adapter; no cross-provider conversion. */
    default String fundingProvider() {
        return name();
    }

    default String fundingAccount() {
        return account();
    }

    /**
     * Explicit maximum company-funded fee that may be reserved before a payout is sent. Null means
     * no validated ceiling is available: fail closed for SasPay.
     */
    default BigDecimal feeReservationCeiling(MerchantPayout payout) {
        return null;
    }

    /** GET-only provider fee quotation. Must not initiate a payout. */
    default BigDecimal payoutFeeQuote(BigDecimal grossAmount, String method) {
        throw new IllegalStateException("PAYOUT_FEE_QUOTE_UNAVAILABLE");
    }

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
            Instant occurredAt,
            BigDecimal providerFee) {
        /** Compatibility constructor for providers/tests without separate fee evidence. */
        public Proof(
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
            this(
                    authenticated,
                    provider,
                    account,
                    businessReference,
                    transactionId,
                    amount,
                    currency,
                    method,
                    phoneE164,
                    status,
                    occurredAt,
                    null);
        }

        @Override
        public String toString() {
            return "MerchantPayoutProof[redacted]";
        }
    }
}
