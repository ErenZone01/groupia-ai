package sn.groupia.payment.domain.model;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.domain.service.*;

/** Pure domain regression: a checkout session is never the financial transaction ID. */
class PaymentHostedSessionTests {
    private static final Instant START = Instant.parse("2026-11-10T05:27:00Z");
    private static final String SESSION = "a9b67211-150f-4c71-885c-123450000001";
    private static final String TRANSACTION = "b9b67211-150f-4c71-885c-123450000002";

    private PaymentRequest request() {
        return PaymentRequest.create(
                CreatePaymentRequest.forDirectOrder(
                        UUID.randomUUID(), UUID.randomUUID(),
                        new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO,
                        START.plusSeconds(86400)),
                START);
    }

    private Payment attempt(PaymentRequest request) {
        var payment = Payment.create(request, "SASPAY", UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), START);
        payment.checkoutContext(new PaymentCheckoutContext(
                "DIR-TEST-1", "Awa Ndiaye", "+221771234567", "awa@example.test"));
        return payment;
    }

    private ProviderObservation observe(
            PaymentRequest request, Payment payment, PaymentStatus state,
            String transaction, ProviderMetadata metadata) {
        return new ProviderObservation(
                "SASPAY", payment.instruction(request).providerAccountReference(),
                payment.instruction(request).merchantReference(),
                transaction, request.getId(), request.getOrderId(), request.getPurpose(),
                new BigDecimal("1000"), "XOF", state, START.plusSeconds(30),
                metadata, request.getTarget());
    }

    @Test
    void pendingCheckoutPersistsSessionWithoutInventingProviderTransaction() {
        var request = request();
        var payment = attempt(request);
        var metadata = new ProviderMetadata(
                "DIR-TEST-1", "https://pay.saspay.me/checkout/synthetic", SESSION, null);
        payment.apply(observe(request, payment, PaymentStatus.PENDING, null, metadata),
                START.plusSeconds(1));
        payment.checkoutDetails(metadata);
        assertThat(payment.instruction(request).providerSessionId()).isEqualTo(SESSION);
        assertThat(payment.instruction(request).providerTransactionId()).isNull();
        assertThat(payment.view().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.view().checkoutUrl()).isNotNull();
        assertThat(payment.getProviderCollectionFee()).isNull();
        assertThat(payment.getProviderCustomerCharged()).isNull();
    }

    @Test
    void cancelledCheckoutIsNotASettlementAndMayCloseItsAttempt() {
        var request = request();
        var payment = attempt(request);
        var pending = new ProviderMetadata("DIR-TEST-1", null, SESSION, null);
        payment.apply(observe(request, payment, PaymentStatus.PENDING, null, pending),
                START.plusSeconds(1));
        payment.checkoutDetails(pending);
        payment.apply(observe(request, payment, PaymentStatus.CANCELLED, null, pending),
                START.plusSeconds(2));
        assertThat(payment.view().status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payment.view().checkoutUrl()).isNull();
        assertThat(payment.instruction(request).providerTransactionId()).isNull();
        assertThat(payment.getProviderCollectionFee()).isNull();
        assertThat(payment.getProviderCustomerCharged()).isNull();
        assertThat(payment.preventsNewAttempt()).isFalse();
    }

    @Test
    void verifiedFinancialProofPersistsTheExactPayerFeeSeparatelyFromPrincipal() {
        var request = request();
        var payment = attempt(request);
        var metadata = new ProviderMetadata(
                "DIR-TEST-1", null, SESSION, null,
                new BigDecimal("37.50"), new BigDecimal("1037.50"));
        payment.checkoutDetails(metadata);
        boolean first = payment.apply(
                observe(request, payment, PaymentStatus.SUCCEEDED, TRANSACTION, metadata),
                START.plusSeconds(5));
        assertThat(first).isTrue();
        assertThat(payment.view().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.instruction(request).providerSessionId()).isEqualTo(SESSION);
        assertThat(payment.instruction(request).providerTransactionId()).isEqualTo(TRANSACTION);
        assertThat(payment.getProviderCollectionFee()).isEqualByComparingTo("37.50");
        assertThat(payment.getProviderCustomerCharged()).isEqualByComparingTo("1037.50");
        assertThat(payment.instruction(request).amount()).isEqualByComparingTo("1000");
        assertThat(payment.apply(
                observe(request, payment, PaymentStatus.SUCCEEDED, TRANSACTION, metadata),
                START.plusSeconds(6))).isFalse();
    }
}
