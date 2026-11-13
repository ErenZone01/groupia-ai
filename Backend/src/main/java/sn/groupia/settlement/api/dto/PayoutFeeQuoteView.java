package sn.groupia.settlement.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.settlement.domain.model.MerchantPayoutFeeQuote;

/** Fee and net displayed to the seller before the payout POST. */
public record PayoutFeeQuoteView(
        UUID quoteId,
        UUID destinationId,
        String method,
        String maskedPhone,
        BigDecimal grossAmount,
        BigDecimal providerFee,
        BigDecimal netAmount,
        String currency,
        String feeChargeMode,
        Instant expiresAt) {
    public static PayoutFeeQuoteView from(MerchantPayoutFeeQuote q) {
        return new PayoutFeeQuoteView(
                q.id(), q.destinationId(), q.method(), q.maskedPhone(),
                q.grossAmount(), q.providerFee(), q.netAmount(),
                "XOF", "DEDUCTED", q.expiresAt());
    }
}
