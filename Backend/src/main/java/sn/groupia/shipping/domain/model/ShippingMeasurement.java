package sn.groupia.shipping.domain.model;
import java.math.BigDecimal; import java.time.Instant; import java.util.*;
import sn.groupia.shipping.api.LogisticsSource;
import sn.groupia.shipping.api.dto.*;
import sn.groupia.shared.api.ApiException;
public final class ShippingMeasurement {
    private ShippingMeasurement(){}
    public static ShippingMeasurementView receive(UUID assessment,UUID previous,MeasurementInput i,UUID actor,Instant now){
        if(i==null)throw invalid();
        var values=Arrays.asList(i.actualWeightKg(),i.chargeableWeightKg(),i.volumeCbm(),i.lengthCm(),i.widthCm(),i.heightCm());
        if(values.stream().allMatch(Objects::isNull))throw invalid();
        for(var v:values)if(v!=null&&(v.signum()<=0 || v.compareTo(new BigDecimal("1000000000000"))>=0 || v.stripTrailingZeros().scale()>18))throw invalid();
        Shipment.validateActual(i.occurredAt(),now);
        String reason=Shipment.clean(i.correctionReason(),1000);
        if(!Objects.equals(previous,i.supersedesMeasurementId()) || (previous!=null && reason==null))
            throw new ApiException(409,"SHIPPING_MEASUREMENT_CORRECTION_REQUIRED","Une correction doit désigner la mesure précédente et préciser son motif.");
        var clean=new MeasurementInput(normalize(i.actualWeightKg()),normalize(i.chargeableWeightKg()),normalize(i.volumeCbm()),normalize(i.lengthCm()),normalize(i.widthCm()),normalize(i.heightCm()),
            i.source()==null?LogisticsSource.UNKNOWN:i.source(),i.occurredAt(),Shipment.clean(i.note(),2000),previous,reason);
        return new ShippingMeasurementView(UUID.randomUUID(),assessment,clean,Objects.requireNonNull(actor),now);
    }
    private static BigDecimal normalize(BigDecimal value){return value==null?null:value.stripTrailingZeros();}
    private static ApiException invalid(){return new ApiException(400,"INVALID_SHIPPING_MEASUREMENT","Renseignez au moins une mesure strictement positive, avec au plus 18 décimales.");}
}
