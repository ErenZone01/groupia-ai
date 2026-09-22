package sn.groupia.payment.api.dto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.payment.api.PaymentStatus;
/** Private tracking-token-protected receipt; checkoutUrl can contain a provider operation token. */
public record PaymentAttemptView(UUID id,PaymentStatus status,BigDecimal expectedAmount,String currency,
        boolean reconciliationRequired,Instant initiatedAt,String checkoutUrl) {
    @Override public String toString(){return "PaymentAttemptView[id="+id+", status="+status+"]";}
}
