package sn.groupia.settlement.domain.model;
import java.time.Instant;
import java.util.UUID;
public record MerchantPayoutDestination(UUID id,UUID organizationId,String method,String phoneE164,
    String holderName,String status,Instant createdAt,Instant updatedAt,UUID createdBy,UUID updatedBy,long version) {
    public String maskedPhone() { return "+221 ••• •• "+phoneE164.substring(phoneE164.length()-4); }
    @Override public String toString() { return "MerchantPayoutDestination[id="+id+", status="+status+"]"; }
}
