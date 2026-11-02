package sn.groupia.payment.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sn.groupia.payment.api.dto.CreatePaymentRequest;
import sn.groupia.payment.domain.model.PaymentRequest;
import sn.groupia.payment.domain.repository.FinancialStore;
import sn.groupia.shared.api.ApiException;

class DirectPaymentInitiationGateTests {
    @Test
    void directOrderNeverReachesPaymentAttemptCreationEvenWithAnIdempotencyKey() {
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        UUID org = UUID.randomUUID();
        var request =
                PaymentRequest.create(
                        CreatePaymentRequest.forDirectOrder(
                                org,
                                UUID.randomUUID(),
                                BigDecimal.valueOf(8500),
                                BigDecimal.valueOf(300),
                                BigDecimal.valueOf(1500),
                                now.plusSeconds(300)),
                        now);
        FinancialStore store = mock(FinancialStore.class);
        when(store.request(request.getId())).thenReturn(Optional.of(request));
        var transactions =
                new PaymentTransactions(
                        store, null, Clock.fixed(now, ZoneOffset.UTC), null, null, null);

        assertThatThrownBy(
                        () ->
                                transactions.claim(
                                        org,
                                        request.getId(),
                                        "BICTORYS",
                                        "sandbox",
                                        "direct-payment-key-0001",
                                        null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("pas encore disponible");
        verify(store).request(request.getId());
        verifyNoMoreInteractions(store);
    }
}
