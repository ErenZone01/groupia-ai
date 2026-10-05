package sn.groupia.payment.api.dto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
/** Private manager projection; only a masked external reference is exposed. */
public record RefundView(UUID id,BigDecimal amount,String currency,String method,String externalMethod,String status,
    Instant initiatedAt,Instant completedAt,boolean reconciliationRequired,String maskedExternalReference) {}

