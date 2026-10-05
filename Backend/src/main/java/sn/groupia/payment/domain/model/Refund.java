package sn.groupia.payment.domain.model;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.payment.api.dto.RefundView;
public record Refund(UUID id,UUID organizationId,UUID orderId,UUID calculationId,BigDecimal amount,String currency,
    String method,String externalMethod,String status,String businessReference,String merchantReference,String provider,
    String providerAccountReference,String providerTransactionId,UUID providerIdempotencyKey,String recipientPhoneSnapshot,
    String recipientNameSnapshot,UUID initiatedBy,Instant initiatedAt,Instant providerOccurredAt,Instant completedAt,
    boolean reconciliationRequired,String reconciliationReason,String manualReference,String note,long version) {
    public RefundView view(){return new RefundView(id,amount,currency,method,externalMethod,status,initiatedAt,completedAt,reconciliationRequired,
        manualReference==null?null:"…"+manualReference.substring(Math.max(0,manualReference.length()-4)));}
    public boolean succeeded(){return "SUCCEEDED".equals(status);}
    @Override public String toString(){return "Refund[id="+id+", status="+status+"]";}
}

