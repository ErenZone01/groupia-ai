package sn.groupia.ordering.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Authorized customer projection, without provider credentials or account references. */
public record CustomerFeeQuote(
        UUID id,
        String provider,
        String network,
        String networkLabel,
        BigDecimal saleAmount,
        BigDecimal collectionFee,
        BigDecimal totalCharged,
        BigDecimal merchantNet,
        BigDecimal groupiaCommission,
        String currency,
        Instant expiresAt) {
    public static CustomerFeeQuote from(sn.groupia.payment.api.dto.PaymentFeeQuoteView q) {
        return q == null
                ? null
                : new CustomerFeeQuote(
                        q.id(),
                        q.provider(),
                        q.network(),
                        q.networkLabel(),
                        q.saleAmount(),
                        q.collectionFee(),
                        q.totalCharged(),
                        q.merchantNet(),
                        q.groupiaCommission(),
                        q.currency(),
                        q.expiresAt());
    }
}
