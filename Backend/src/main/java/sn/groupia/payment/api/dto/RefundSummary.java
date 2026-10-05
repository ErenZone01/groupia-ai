package sn.groupia.payment.api.dto;
import java.math.BigDecimal;
import java.time.Instant;
/** Customer-safe projection, without internal financial identifiers. */
public record RefundSummary(BigDecimal amount,String currency,String method,String externalMethod,String status,
    Instant initiatedAt,Instant completedAt,boolean reconciliationRequired) {
    public static RefundSummary from(RefundView r){return new RefundSummary(r.amount(),r.currency(),r.method(),r.externalMethod(),r.status(),r.initiatedAt(),r.completedAt(),r.reconciliationRequired());}
}

