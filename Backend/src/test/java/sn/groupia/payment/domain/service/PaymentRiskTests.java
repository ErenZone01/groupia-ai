package sn.groupia.payment.domain.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.domain.model.*;

class PaymentRiskTests {
    PaymentRequest request;
    Payment payment;

    @BeforeEach
    void setup() {
        request = mock(PaymentRequest.class);
        payment = mock(Payment.class);
        UUID r = UUID.randomUUID(), p = UUID.randomUUID(), org = UUID.randomUUID();
        when(request.getId()).thenReturn(r);
        when(request.getOrganizationId()).thenReturn(org);
        when(request.getOrderId()).thenReturn(UUID.randomUUID());
        when(request.getSettledPaymentId()).thenReturn(p);
        when(request.getAcceptanceStatus()).thenReturn(AcceptanceStatus.CLOSED);
        when(request.getExpectedAmount()).thenReturn(new BigDecimal("500"));
        when(request.getCurrency()).thenReturn("XOF");
        when(payment.getId()).thenReturn(p);
        when(payment.getPaymentRequestId()).thenReturn(r);
        when(payment.getOrganizationId()).thenReturn(org);
        when(payment.getStatus()).thenReturn(PaymentStatus.SUCCEEDED);
        when(payment.getProviderTransactionId()).thenReturn("TX-A");
        when(payment.getExpectedAmount()).thenReturn(new BigDecimal("500"));
        when(payment.getConfirmedAmount()).thenReturn(new BigDecimal("500"));
        when(payment.getCurrency()).thenReturn("XOF");
        when(payment.getConfirmedCurrency()).thenReturn("XOF");
    }

    @Test
    void consistentPrincipalIsCompatible() {
        assertThat(PaymentRisk.reasons(request, List.of(payment), List.of())).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "PROVIDER_REVERSAL,PAYMENT_PROVIDER_REVERSAL",
        "PROVIDER_STATE_UNCERTAIN,PAYMENT_PROVIDER_STATE_UNCERTAIN",
        "TRANSACTION_MISMATCH,PAYMENT_TRANSACTION_INCONSISTENT"
    })
    void classifiesUncertaintyWithoutMaskingIt(String reason, String code) {
        when(payment.isReconciliationRequired()).thenReturn(true);
        when(payment.getReconciliationReason()).thenReturn(reason);
        assertThat(PaymentRisk.reasons(request, List.of(payment), List.of()))
                .contains(code, "PAYMENT_RECONCILIATION_REQUIRED");
    }

    @Test
    void mismatchedPrincipalCannotBeAllocated() {
        when(payment.getConfirmedAmount()).thenReturn(new BigDecimal("499"));
        assertThat(PaymentRisk.reasons(request, List.of(payment), List.of()))
                .contains("PAYMENT_INCONSISTENT", "PAYMENT_ALLOCATION_INCONSISTENT");
    }

    @Test
    void missingTransactionCannotBeAllocated() {
        when(payment.getProviderTransactionId()).thenReturn(null);
        assertThat(PaymentRisk.reasons(request, List.of(payment), List.of()))
                .contains("PAYMENT_ALLOCATION_INCONSISTENT");
    }

    @Test
    void successfulNonPrincipalWithoutExactReviewBlocks() {
        when(request.getSettledPaymentId()).thenReturn(UUID.randomUUID());
        assertThat(PaymentRisk.reasons(request, List.of(payment), List.of()))
                .contains("PAYMENT_UNALLOCATED_SUCCESS");
    }
}
