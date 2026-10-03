package sn.groupia.shipping.domain.service;
import java.math.*;
import sn.groupia.shipping.api.*;
import sn.groupia.shipping.api.dto.*;
import sn.groupia.shared.api.ApiException;
/** Exact rational quantity through the monetary rounding step; decimal quantities are display-only. */
public final class FinalShippingCalculator {
    private FinalShippingCalculator(){}
    public static ShippingExplanation calculate(AssessmentSeed seed,FinalShippingInput input,ShippingMeasurementView measurement) {
        var t=seed.terms();
        if(input.origin()==null)throw invalid();
        if(input.currency()!=null && !t.shippingCurrency().equals(input.currency()))throw invalid();
        if(input.origin()==CalculationOrigin.PROVIDER_QUOTED){
            if(input.source()==null || input.quotedAmount()==null || !"XOF".equals(input.currency()) || input.measurementId()!=null
                || input.quotedAmount().stripTrailingZeros().scale()>0)throw invalid();
            var amount=money(input.quotedAmount());
            return new ShippingExplanation(input.origin(),t.transportMode(),t.shippingPricingMethod(),"PROVIDER_QUOTED",
                null,null,null,t.shippingCurrency(),amount,"HALF_UP",input.source()==null?LogisticsSource.UNKNOWN:input.source(),
                null,null,null,null,null,null,null,null,null,null);
        }
        if(input.quotedAmount()!=null)throw invalid();
        var m=measurement==null?null:measurement.information();
        BigDecimal numerator=BigDecimal.ONE,denominator=BigDecimal.ONE,volumetric=null;
        String method,unit;
        switch(t.shippingPricingMethod()){
            case FIXED -> {method="FIXED";unit="ORDER";if(measurement!=null)throw invalid();}
            case PER_ITEM -> {method="ITEM_COUNT";unit="ITEM";numerator=BigDecimal.valueOf(seed.totalItems());if(measurement!=null||seed.totalItems()<1)throw invalid();}
            case PER_CBM -> {
                unit="CBM";if(m==null)throw missing();
                if(m.volumeCbm()!=null){numerator=m.volumeCbm();method="COMMUNICATED_VOLUME";}
                else {numerator=dimensions(m);denominator=new BigDecimal("1000000");method="DIMENSION_VOLUME";}
            }
            case PER_KG -> {
                unit="KG";if(m==null)throw missing();
                if(m.chargeableWeightKg()!=null){numerator=m.chargeableWeightKg();method="COMMUNICATED_CHARGEABLE_WEIGHT";}
                else if(t.volumetricDivisor()!=null){
                    if(m.actualWeightKg()==null)throw missing();
                    BigDecimal volume=dimensions(m);
                    volumetric=volume.divide(t.volumetricDivisor(),MathContext.DECIMAL128);
                    numerator=m.actualWeightKg().multiply(t.volumetricDivisor()).max(volume);
                    denominator=t.volumetricDivisor();method="MAX_ACTUAL_VOLUMETRIC";
                } else {
                    if(m.actualWeightKg()==null)throw missing();
                    numerator=m.actualWeightKg();method="ACTUAL_WEIGHT";
                }
            }
            default -> throw invalid();
        }
        BigDecimal amount=money(numerator.multiply(t.shippingRate()).divide(denominator,0,RoundingMode.HALF_UP));
        return new ShippingExplanation(CalculationOrigin.CALCULATED,t.transportMode(),t.shippingPricingMethod(),method,t.shippingRate(),
            numerator.divide(denominator,MathContext.DECIMAL128),unit,t.shippingCurrency(),amount,"HALF_UP",
            m==null?LogisticsSource.MANAGER:m.source(),m==null?null:m.actualWeightKg(),m==null?null:m.chargeableWeightKg(),
            m==null?null:m.volumeCbm(),m==null?null:m.lengthCm(),m==null?null:m.widthCm(),m==null?null:m.heightCm(),
            method.equals("MAX_ACTUAL_VOLUMETRIC")?t.volumetricDivisor():null,volumetric,numerator,denominator);
    }
    private static BigDecimal dimensions(MeasurementInput m){
        if(m.lengthCm()==null||m.widthCm()==null||m.heightCm()==null)throw missing();
        return m.lengthCm().multiply(m.widthCm()).multiply(m.heightCm());
    }
    private static BigDecimal money(BigDecimal a){
        if(a.signum()<0||a.compareTo(new BigDecimal("1000000000000000000"))>=0)throw invalid();
        return a.setScale(0,RoundingMode.UNNECESSARY);
    }
    private static ApiException missing(){return new ApiException(409,"SHIPPING_MEASUREMENT_REQUIRED","Les informations disponibles ne suffisent pas à ce calcul.");}
    private static ApiException invalid(){return new ApiException(400,"INVALID_SHIPPING_CALCULATION","Vérifiez le mode de calcul, le montant et la devise.");}
}
