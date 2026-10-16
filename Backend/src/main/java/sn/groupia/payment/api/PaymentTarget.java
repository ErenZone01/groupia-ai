package sn.groupia.payment.api;

import java.util.Objects;
import java.util.UUID;

public record PaymentTarget(PaymentTargetType type, UUID id) {
    public PaymentTarget {
        Objects.requireNonNull(type);
        Objects.requireNonNull(id);
    }

    public static PaymentTarget order(UUID id) {
        return new PaymentTarget(PaymentTargetType.ORDER, id);
    }

    public static PaymentTarget subscription(UUID id) {
        return new PaymentTarget(PaymentTargetType.SUBSCRIPTION_INVOICE, id);
    }
}
