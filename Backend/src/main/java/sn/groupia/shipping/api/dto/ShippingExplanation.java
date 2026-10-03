package sn.groupia.shipping.api.dto;
import java.math.BigDecimal;
import sn.groupia.shipping.api.*;
/** Explicit customer-safe allowlist; no actor, identifiers, private notes or contact. */
public record ShippingExplanation(CalculationOrigin origin,TransportMode transportMode,ShippingPricingMethod pricingMethod,
    String method,BigDecimal rate,BigDecimal billableQuantity,String billableUnit,String currency,BigDecimal amount,
    String roundingRule,LogisticsSource source,BigDecimal actualWeightKg,BigDecimal communicatedChargeableWeightKg,
    BigDecimal volumeCbm,BigDecimal lengthCm,BigDecimal widthCm,BigDecimal heightCm,BigDecimal volumetricDivisor,
    BigDecimal volumetricWeightKg,BigDecimal quantityNumerator,BigDecimal quantityDenominator) {}
