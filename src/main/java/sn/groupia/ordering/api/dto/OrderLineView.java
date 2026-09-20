package sn.groupia.ordering.api.dto;
import java.math.BigDecimal;
import java.util.*;
public record OrderLineView(int lineNumber, String productNameSnapshot, String productDescriptionSnapshot,
        String commercialLabelSnapshot, Map<String, String> variantAttributesSnapshot, String skuSnapshot,
        String saleUnitSnapshot, int quantity, BigDecimal unitPriceSnapshot, BigDecimal lineTotal,
        BigDecimal estimatedUnitWeightKgSnapshot, BigDecimal estimatedUnitVolumeM3Snapshot) {}
