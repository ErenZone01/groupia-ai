package sn.groupia.shipping.api.dto;
import java.math.BigDecimal;
public record ShippingEstimateLine(int quantity, BigDecimal unitWeightKg, BigDecimal unitVolumeM3) {}
