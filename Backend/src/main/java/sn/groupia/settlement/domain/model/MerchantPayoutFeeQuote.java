package sn.groupia.settlement.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable seller-facing payout quote, bound to one target and exact recipient. */
public record MerchantPayoutFeeQuote(
        UUID id,
        UUID organizationId,
        UUID campaignId,
        UUID directOrderId,
        UUID destinationId,
        String method,
        String phoneE164,
        String provider,
        String providerAccountReference,
        BigDecimal grossAmount,
        BigDecimal providerFee,
        BigDecimal netAmount,
        Instant expiresAt) {
    public String feeChargeMode() {
        return "DEDUCTED";
    }

    public String currency() {
        return "XOF";
    }

    public String maskedPhone() {
        return "+221 ••• •• " + phoneE164.substring(phoneE164.length() - 4);
    }
}
