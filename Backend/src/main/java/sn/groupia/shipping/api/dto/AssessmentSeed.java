package sn.groupia.shipping.api.dto;
import java.math.BigDecimal; import java.util.UUID;
/** Trusted immutable order snapshots supplied by ordering, never by the HTTP body. */
public record AssessmentSeed(UUID organizationId,UUID campaignId,UUID orderId,ShippingTerms terms,long totalItems,
    BigDecimal estimatedWeightKg,BigDecimal estimatedVolumeCbm,BigDecimal estimatedShippingAmount) {}
