package sn.groupia.shipping.api.dto;
import java.math.BigDecimal; import java.time.Instant; import java.util.UUID;
import sn.groupia.shipping.api.*;
public record FinalShippingInput(long version,CalculationOrigin origin,UUID measurementId,BigDecimal quotedAmount,
    String currency,LogisticsSource source,Instant occurredAt,String note,String correctionReason) {}
