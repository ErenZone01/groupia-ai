package sn.groupia.shipping.api.dto;
import java.math.BigDecimal; import java.time.Instant; import java.util.UUID;
import sn.groupia.shipping.api.LogisticsSource;
public record MeasurementInput(BigDecimal actualWeightKg,BigDecimal chargeableWeightKg,BigDecimal volumeCbm,
    BigDecimal lengthCm,BigDecimal widthCm,BigDecimal heightCm,LogisticsSource source,Instant occurredAt,
    String note,UUID supersedesMeasurementId,String correctionReason) {}
