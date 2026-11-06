package sn.groupia.subscription.api;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommercialRevenuePolicyTests {
    @Test
    void subscriptionOnlyModelIgnoresLegacy300XofEnvironmentValue() {
        var policy = new CommercialRevenuePolicy(new BigDecimal("300"), false);

        assertThat(policy.transactionFeeXof()).isEqualByComparingTo("0");
        assertThat(policy.transactionFeeXofLong()).isZero();
    }

    @Test
    void explicitOptInIsTheOnlyWayToChargeTheConfiguredTransactionFee() {
        var policy = new CommercialRevenuePolicy(new BigDecimal("300"), true);

        assertThat(policy.transactionFeeXof()).isEqualByComparingTo("300");
        assertThat(policy.transactionFeeXofLong()).isEqualTo(300L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "0.5", "100000000000000"})
    void invalidConfiguredFeeIsRejectedEvenWhenTransactionFeesAreOff(String raw) {
        assertThatThrownBy(() -> new CommercialRevenuePolicy(new BigDecimal(raw), false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
