package sn.groupia.shipping.application.service;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import sn.groupia.shipping.api.*;
import sn.groupia.shipping.api.dto.*;
import sn.groupia.shared.api.ApiException;
import static org.assertj.core.api.Assertions.*;

class ShippingEstimatorTests {
    private final ShippingEstimator calculator = new DefaultShippingEstimator();
    @ParameterizedTest @CsvSource({"PER_KG,1000,667", "PER_CBM,1000000,250", "PER_ITEM,125.25,251", "FIXED,125,125"})
    void estimatesFourMethodsWithoutIntermediateRounding(String method,String rate,String expected) {
        var lines = List.of(new ShippingEstimateLine(1,new BigDecimal("0.3335"),new BigDecimal("0.000125")),
                new ShippingEstimateLine(1,new BigDecimal("0.3335"),new BigDecimal("0.000125")));
        assertThat(calculator.estimate(terms(method,"ESTIMATED_AT_CHECKOUT",rate),lines))
                .isEqualByComparingTo(expected).hasScaleOf(0);
    }
    @ParameterizedTest @ValueSource(strings = {"PER_KG","PER_CBM"})
    void missingRequiredEstimatesFailButDeferredEstimatesCanBeUnknown(String method) {
        var lines = List.of(new ShippingEstimateLine(1,null,null));
        assertThatThrownBy(() -> calculator.estimate(terms(method,"ESTIMATED_AT_CHECKOUT","1000"),lines))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("SHIPPING_ESTIMATE_UNAVAILABLE"));
        assertThat(calculator.estimate(terms(method,"AFTER_FINAL_ASSESSMENT","1000"),lines)).isNull();
    }
    @ParameterizedTest @ValueSource(strings = {"0","-0.001"})
    void zeroOrNegativeWeightsAreNeverUsed(String weight) {
        assertThatThrownBy(() -> calculator.estimate(terms("PER_KG","AFTER_FINAL_ASSESSMENT","1000"),
                List.of(new ShippingEstimateLine(1,new BigDecimal(weight),null)))).isInstanceOf(ApiException.class);
    }
    @ParameterizedTest @ValueSource(ints = {0,-1})
    void rejectsInvalidQuantity(int quantity) {
        assertThatThrownBy(() -> calculator.estimate(terms("FIXED","AFTER_FINAL_ASSESSMENT","1000"),
                List.of(new ShippingEstimateLine(quantity,null,null)))).isInstanceOf(ApiException.class);
    }
    @Test void fixedZeroIsAllowed() {
        assertThat(calculator.estimate(terms("FIXED","ESTIMATED_AT_CHECKOUT","0"),List.of(new ShippingEstimateLine(3,null,null)))).isZero();
    }
    @Test void unsupportedCurrencyAndRoundingAreRejected() {
        assertThatThrownBy(() -> new ShippingTerms(null,TransportMode.AIR,ShippingPricingMethod.PER_KG,
                ShippingCollectionMode.ESTIMATED_AT_CHECKOUT,BigDecimal.ONE,"EUR","HALF_UP")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShippingTerms(null,TransportMode.AIR,ShippingPricingMethod.PER_KG,
                ShippingCollectionMode.ESTIMATED_AT_CHECKOUT,BigDecimal.ONE,"XOF","DOWN")).isInstanceOf(IllegalArgumentException.class);
    }
    private ShippingTerms terms(String method,String collection,String rate) {
        return new ShippingTerms(null,TransportMode.AIR,ShippingPricingMethod.valueOf(method),
                ShippingCollectionMode.valueOf(collection),new BigDecimal(rate),"XOF","HALF_UP");
    }
}
