package sn.groupia.payment.api.dto;

import java.util.UUID;

/** Returned only after the caller authenticates the obligation or its private capability. */
public record PaymentAttemptRecoveryView(
        String provider, UUID feeQuoteId, PaymentAttemptView attempt, PaymentFeeQuoteView quote) {
    @Override
    public String toString() {
        return "PaymentAttemptRecoveryView[redacted]";
    }
}
