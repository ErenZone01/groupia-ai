package sn.groupia.payment.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Server-calculated total; the browser accepts the opaque quote ID, never supplies amounts. */
public record PaymentFeeQuoteView(
        UUID id,
        String provider,
        String network,
        String networkLabel,
        BigDecimal saleAmount,
        BigDecimal groupiaCommission,
        BigDecimal collectionFee,
        BigDecimal totalCharged,
        BigDecimal merchantNet,
        String currency,
        Instant expiresAt) {}
