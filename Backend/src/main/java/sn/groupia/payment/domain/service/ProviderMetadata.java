package sn.groupia.payment.domain.service;

import java.math.BigDecimal;

/** Provider-neutral operational details; a charge/session identifier is not a transaction ID. */
public record ProviderMetadata(
        String paymentReference,
        String checkoutUrl,
        String chargeId,
        ReviewReason reviewReason,
        BigDecimal collectionFee,
        BigDecimal customerCharged) {
    public enum ReviewReason {
        PROVIDER_REVERSAL,
        AUTHORIZATION_NOT_CAPTURED,
        PROVIDER_STATUS_UNKNOWN
    }

    public ProviderMetadata(
            String paymentReference,
            String checkoutUrl,
            String chargeId,
            ReviewReason reviewReason) {
        this(paymentReference, checkoutUrl, chargeId, reviewReason, null, null);
    }

    @Override
    public String toString() {
        return "ProviderMetadata[redacted]";
    }
}
