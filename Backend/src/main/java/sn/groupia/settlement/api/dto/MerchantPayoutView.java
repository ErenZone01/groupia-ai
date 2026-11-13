package sn.groupia.settlement.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MerchantPayoutView(
        UUID id,
        UUID campaignId,
        BigDecimal amount,
        String currency,
        String status,
        String method,
        String maskedPhone,
        String holderName,
        Instant requestedAt,
        Instant succeededAt,
        boolean reconciliationRequired,
        String reconciliationReason,
        UUID directOrderId,
        BigDecimal providerFee,
        BigDecimal netAmount,
        String feeChargeMode) {
    public MerchantPayoutView(
            UUID id, UUID campaignId, BigDecimal amount, String currency, String status,
            String method, String maskedPhone, String holderName, Instant requestedAt,
            Instant succeededAt, boolean reconciliationRequired, String reconciliationReason,
            UUID directOrderId) {
        this(id, campaignId, amount, currency, status, method, maskedPhone, holderName,
                requestedAt, succeededAt, reconciliationRequired, reconciliationReason,
                directOrderId, null, null, null);
    }

    public MerchantPayoutView(
            UUID id,
            UUID campaignId,
            BigDecimal amount,
            String currency,
            String status,
            String method,
            String maskedPhone,
            String holderName,
            Instant requestedAt,
            Instant succeededAt,
            boolean reconciliationRequired,
            String reconciliationReason) {
        this(
                id,
                campaignId,
                amount,
                currency,
                status,
                method,
                maskedPhone,
                holderName,
                requestedAt,
                succeededAt,
                reconciliationRequired,
                reconciliationReason,
                null);
    }
}
