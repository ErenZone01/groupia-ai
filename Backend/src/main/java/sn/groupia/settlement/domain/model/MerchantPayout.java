package sn.groupia.settlement.domain.model;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record MerchantPayout(UUID id,UUID organizationId,UUID campaignId,BigDecimal amount,String currency,
    String status,UUID destinationId,String method,String phoneE164,String holderName,String provider,
    String providerAccountReference,String businessReference,UUID providerIdempotencyKey,String providerTransactionId,
    UUID requestedBy,Instant requestedAt,Instant providerOccurredAt,Instant succeededAt,
    boolean reconciliationRequired,String reconciliationReason,long version) {
    public boolean reserved() { return java.util.Set.of("CREATED","PENDING","REVIEW_REQUIRED").contains(status); }
    public boolean succeeded() { return status.equals("SUCCEEDED"); }
    public String maskedPhone() { return "+221 ••• •• "+phoneE164.substring(phoneE164.length()-4); }
    @Override public String toString() { return "MerchantPayout[id="+id+", status="+status+"]"; }
}
