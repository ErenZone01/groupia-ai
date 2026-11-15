package sn.groupia.payment.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.payment.api.dto.PaymentFeeQuoteView;

public record PaymentFeeQuote(
        UUID id,
        UUID organizationId,
        UUID requestId,
        String provider,
        String account,
        String country,
        String network,
        String label,
        BigDecimal amount,
        BigDecimal fee,
        String currency,
        Instant expiresAt) {
    public PaymentFeeQuoteView view() {
        return new PaymentFeeQuoteView(
                id,
                provider,
                network,
                label,
                amount,
                BigDecimal.ZERO,
                fee,
                amount.add(fee),
                amount,
                currency,
                expiresAt);
    }
}
