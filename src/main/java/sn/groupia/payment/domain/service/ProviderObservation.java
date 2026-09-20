package sn.groupia.payment.domain.service;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.payment.api.*;
/** Financial evidence normalized by an authenticated adapter; missing facts never imply success. */
public record ProviderObservation(String provider, String providerAccountReference, String merchantReference,
        String providerTransactionId, UUID paymentRequestId, UUID orderId, PaymentPurpose purpose,
        BigDecimal amount, String currency, PaymentStatus status, Instant occurredAt) {}
