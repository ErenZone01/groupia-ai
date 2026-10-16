package sn.groupia.subscription.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.payment.api.PaymentOperationalStatus;

public record SubscriptionInvoiceView(
        UUID id,
        UUID organizationId,
        String reference,
        String billingPeriodKey,
        Instant periodStart,
        Instant periodEnd,
        String billingTimezone,
        BigDecimal amount,
        String currency,
        String status,
        UUID paymentRequestId,
        UUID settledPaymentId,
        Instant paidAt,
        PaymentOperationalStatus paymentStatus,
        boolean attemptInProgress,
        boolean reconciliationRequired,
        boolean canPay) {}
