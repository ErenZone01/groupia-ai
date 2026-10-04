package sn.groupia.ordering.api.dto;
import java.math.BigDecimal;import java.time.Instant;import java.util.UUID;
/** Customer-facing checkout receipt, authorized by customeraccess through ordering. */
public record CustomerPaymentAttempt(UUID id,String status,BigDecimal expectedAmount,String currency,
    boolean reconciliationRequired,Instant initiatedAt,String checkoutUrl) {
    @Override public String toString(){return "CustomerPaymentAttempt[id="+id+", status="+status+"]";}
}
