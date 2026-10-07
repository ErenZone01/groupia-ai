package sn.groupia.settlement.api.dto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record MerchantPayoutView(UUID id,UUID campaignId,BigDecimal amount,String currency,String status,
    String method,String maskedPhone,String holderName,Instant requestedAt,Instant succeededAt,
    boolean reconciliationRequired,String reconciliationReason) {}
