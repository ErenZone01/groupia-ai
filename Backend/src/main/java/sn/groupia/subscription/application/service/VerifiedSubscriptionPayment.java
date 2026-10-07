package sn.groupia.subscription.application.service;

import java.math.BigDecimal;
import java.time.Instant;

record VerifiedSubscriptionPayment(
        String provider,
        String providerTransactionId,
        String providerReference,
        BigDecimal amount,
        String currency,
        Instant paidAt) {}
