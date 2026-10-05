package sn.groupia.payment.api.dto;
import java.math.BigDecimal;
import java.util.UUID;
/** Server-owned financial and recipient snapshots, never an HTTP input. */
public record ReserveRefund(UUID organizationId,UUID orderId,UUID calculationId,BigDecimal amount,String currency,
    String recipientPhone,String recipientName,UUID actor,String method,String externalMethod,String reference,String note) {
    @Override public String toString(){return "ReserveRefund[redacted]";}
}

