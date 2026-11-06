package sn.groupia.subscription.api;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single commercial policy for both groupage and direct sales.
 * Legacy configured fees are retained for historical deployments, but new orders
 * do not charge them unless an explicit, independently reviewed opt-in is enabled.
 * Paid subscriptions are managed separately by the subscription invoice service.
 */
@Component
public final class CommercialRevenuePolicy {
    private static final BigDecimal MAX_FEE_EXCLUSIVE = new BigDecimal("100000000000000");

    private final BigDecimal transactionFeeXof;

    public CommercialRevenuePolicy(
            @Value("${groupia.ordering.platform-fee-xof:300}") BigDecimal configuredFeeXof,
            @Value("${groupia.commerce.transaction-fee-enabled:false}") boolean transactionFeeEnabled) {
        if (configuredFeeXof == null
                || configuredFeeXof.signum() < 0
                || configuredFeeXof.stripTrailingZeros().scale() > 0
                || configuredFeeXof.compareTo(MAX_FEE_EXCLUSIVE) >= 0) {
            throw new IllegalArgumentException("Invalid XOF transaction fee configuration");
        }
        this.transactionFeeXof =
                transactionFeeEnabled ? configuredFeeXof.setScale(0) : BigDecimal.ZERO;
    }

    public BigDecimal transactionFeeXof() {
        return transactionFeeXof;
    }

    public long transactionFeeXofLong() {
        return transactionFeeXof.longValueExact();
    }
}
