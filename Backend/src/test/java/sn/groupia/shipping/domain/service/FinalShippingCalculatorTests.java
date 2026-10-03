package sn.groupia.shipping.domain.service;
import java.math.*;import java.time.*;import java.util.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.params.ParameterizedTest;import org.junit.jupiter.params.provider.CsvSource;
import sn.groupia.shipping.api.*;import sn.groupia.shipping.api.dto.*;import sn.groupia.shipping.domain.model.ShippingMeasurement;
import sn.groupia.shared.api.ApiException;import static org.assertj.core.api.Assertions.*;
class FinalShippingCalculatorTests {
 BigDecimal d(String s){return s==null?null:new BigDecimal(s);}
 AssessmentSeed seed(String method,String rate,String divisor){return new AssessmentSeed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
 new ShippingTerms(null,TransportMode.AIR,ShippingPricingMethod.valueOf(method),ShippingCollectionMode.AFTER_FINAL_ASSESSMENT,d(rate),"XOF","HALF_UP",d(divisor)),4,d("1.2"),d("0.15"),d("4200"));}
 ShippingMeasurementView m(String w,String c,String v,String l,String b,String h){return ShippingMeasurement.receive(UUID.randomUUID(),null,
 new MeasurementInput(d(w),d(c),d(v),d(l),d(b),d(h),LogisticsSource.FORWARDER,null,"private",null,null),UUID.randomUUID(),Instant.now());}
 ShippingExplanation calc(AssessmentSeed s,ShippingMeasurementView m){return FinalShippingCalculator.calculate(s,new FinalShippingInput(0,CalculationOrigin.CALCULATED,m==null?null:m.id(),null,null,null,null,null,null),m);}
 @ParameterizedTest @CsvSource({"2.8,3500,9800","0.123456789,1000,123","0.00015,10000,2","1.0005,1000,1001"})
 void preciseWeightHalfUp(String weight,String rate,String amount){var r=calc(seed("PER_KG",rate,null),m(weight,null,null,null,null,null));assertThat(r.amount()).isEqualByComparingTo(amount);assertThat(r.billableQuantity()).isEqualByComparingTo(weight);}
 @ParameterizedTest @CsvSource({"5000,8.4,29400","6000,7,24500","7000,6,21000","7,6000,21000000"})
 void configuredDivisor(String divisor,String weight,String amount){var r=calc(seed("PER_KG","3500",divisor),m("2.4",null,null,"60","35","20"));assertThat(r.billableQuantity()).isEqualByComparingTo(weight);assertThat(r.amount()).isEqualByComparingTo(amount);}
 @Test void rationalRounding(){var r=calc(seed("PER_KG","1.05","7"),m("0.01",null,null,"1","1","10"));assertThat(r.amount()).isEqualByComparingTo("2");assertThat(r.quantityNumerator()).isEqualByComparingTo("10");assertThat(r.quantityDenominator()).isEqualByComparingTo("7");}
 @Test void communicatedWeightAndActualMaximum(){var r=calc(seed("PER_KG","3500","7000"),m(null,"8.4",null,null,null,null));assertThat(r.amount()).isEqualByComparingTo("29400");assertThat(r.volumetricWeightKg()).isNull();assertThat(r.actualWeightKg()).isNull();
 assertThat(calc(seed("PER_KG","3500","5000"),m("10",null,null,"60","35","20")).amount()).isEqualByComparingTo("35000");}
 @Test void noImplicitVolumetric(){var r=calc(seed("PER_KG","3500",null),m("2.4",null,null,"60","35","20"));assertThat(r.amount()).isEqualByComparingTo("8400");assertThat(r.volumetricWeightKg()).isNull();}
 @Test void cbmDirectAndDimensions(){var a=calc(seed("PER_CBM","100000",null),m(null,null,"0.165",null,null,null));var b=calc(seed("PER_CBM","100000",null),m(null,null,null,"100","55","30"));assertThat(a.amount()).isEqualByComparingTo("16500");assertThat(b.amount()).isEqualByComparingTo(a.amount());}
 @Test void itemAndFixed(){assertThat(calc(seed("PER_ITEM","1000",null),null).amount()).isEqualByComparingTo("4000");assertThat(calc(seed("FIXED","2500",null),null).amount()).isEqualByComparingTo("2500");}
 @Test void quotedHasNoFormula(){var r=FinalShippingCalculator.calculate(seed("PER_KG","3500",null),new FinalShippingInput(0,CalculationOrigin.PROVIDER_QUOTED,null,d("12500"),"XOF",LogisticsSource.FORWARDER,null,null,null),null);assertThat(r.amount()).isEqualByComparingTo("12500");assertThat(r.rate()).isNull();assertThat(r.billableQuantity()).isNull();assertThat(r.quantityNumerator()).isNull();}
 @ParameterizedTest @CsvSource({"EUR,12500","XOF,-1","XOF,12.5","XOF,1000000000000000000"})
 void invalidQuote(String currency,String amount){assertThatThrownBy(()->FinalShippingCalculator.calculate(seed("FIXED","2500",null),new FinalShippingInput(0,CalculationOrigin.PROVIDER_QUOTED,null,d(amount),currency,LogisticsSource.FORWARDER,null,null,null),null)).isInstanceOf(ApiException.class);}
 @ParameterizedTest @CsvSource({"0","-1","1000000000000","0.0000000000000000001"})
 void invalidPhysical(String v){assertThatThrownBy(()->m(v,null,null,null,null,null)).isInstanceOf(ApiException.class);}
 @Test void nullAndIncomplete(){assertThatThrownBy(()->m(null,null,null,null,null,null)).isInstanceOf(ApiException.class);var v=m(null,null,null,"10",null,null);assertThat(v.information().actualWeightKg()).isNull();assertThatThrownBy(()->calc(seed("PER_CBM","1000",null),v)).isInstanceOf(ApiException.class);assertThatThrownBy(()->calc(seed("PER_KG","3500","5000"),m("2.4",null,null,null,null,null))).isInstanceOf(ApiException.class);}
}
