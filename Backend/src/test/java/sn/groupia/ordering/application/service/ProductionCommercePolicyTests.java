package sn.groupia.ordering.application.service;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProductionCommercePolicyTests {
    @Test
    void productionAllowsOnlyZeroCommissionWithoutChangingHistoricalOrders() {
        assertThatCode(() -> new ProductionCommercePolicy(BigDecimal.ZERO))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new ProductionCommercePolicy(new BigDecimal("300")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProductionCommercePolicy(new BigDecimal("-1")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProductionCommercePolicy(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
