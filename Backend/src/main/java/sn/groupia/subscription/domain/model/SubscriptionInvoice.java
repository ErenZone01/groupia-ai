package sn.groupia.subscription.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SubscriptionInvoice(
        UUID id,
        UUID organizationId,
        UUID subscriptionId,
        String billingPeriodKey,
        String reference,
        String planCode,
        Instant periodStart,
        Instant periodEnd,
        String billingTimezone,
        BigDecimal amount,
        String currency,
        String billingInterval,
        String billingName,
        String billingPhoneE164,
        String billingEmail,
        String billingCountry,
        String billingLocale,
        UUID paymentRequestId,
        String status,
        UUID settledPaymentId,
        Instant paidAt,
        String creationKey,
        Instant createdAt,
        Instant expiredAt,
        long version) {
    @Override
    public String toString() {
        return "SubscriptionInvoice[id=" + id + ", status=" + status + "]";
    }
}
