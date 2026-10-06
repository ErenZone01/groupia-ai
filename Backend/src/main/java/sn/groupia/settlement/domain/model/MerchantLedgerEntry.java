package sn.groupia.settlement.domain.model;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record MerchantLedgerEntry(UUID id,UUID organizationId,UUID campaignId,UUID orderId,
    String type,String direction,BigDecimal amount,String currency,BigDecimal platformFeeAmount,
    String businessReference,String sourceType,UUID sourceId,Instant recordedAt) {
    public BigDecimal signedAmount() { return direction.equals("CREDIT")?amount:amount.negate(); }
}
