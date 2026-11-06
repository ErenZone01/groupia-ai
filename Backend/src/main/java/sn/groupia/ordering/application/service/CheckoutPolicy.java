package sn.groupia.ordering.application.service;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import sn.groupia.subscription.api.CommercialRevenuePolicy;
import org.springframework.stereotype.Component;

@Component
public class CheckoutPolicy {
    private final BigDecimal fee;
    private final Duration paymentWindow;
    private final Duration trackingLifetime;
    @Autowired
    public CheckoutPolicy(CommercialRevenuePolicy pricing,
            @Value("${groupia.ordering.payment-window-minutes}") long paymentMinutes,
            @Value("${groupia.ordering.tracking-lifetime-days}") long trackingDays) {
        this(pricing.transactionFeeXof(), paymentMinutes, trackingDays);
    }

    /** Explicit fee constructor retains precise historical snapshot unit-test scenarios. */
    CheckoutPolicy(BigDecimal fee, long paymentMinutes, long trackingDays) {
        if (fee.signum() < 0 || fee.stripTrailingZeros().scale() > 0
                || fee.compareTo(new BigDecimal("1000000000000000000")) >= 0
                || paymentMinutes < 1 || paymentMinutes > 10080 || trackingDays < 1 || trackingDays > 730) {
            throw new IllegalArgumentException("Invalid ordering configuration");
        }
        this.fee = fee.setScale(0); this.paymentWindow = Duration.ofMinutes(paymentMinutes);
        this.trackingLifetime = Duration.ofDays(trackingDays);
    }
    public BigDecimal fee() { return fee; }
    public Map<String, String> feeRule() { return Map.of("type", "FIXED", "currency", "XOF", "amount", fee.toPlainString(), "version", "1"); }
    public Duration paymentWindow() { return paymentWindow; }
    public Duration trackingLifetime() { return trackingLifetime; }
}
