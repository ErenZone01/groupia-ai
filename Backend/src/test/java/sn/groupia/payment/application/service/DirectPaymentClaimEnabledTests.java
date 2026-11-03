package sn.groupia.payment.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import sn.groupia.payment.api.DirectPaymentClaimGuard;
import sn.groupia.payment.api.dto.CreatePaymentRequest;
import sn.groupia.payment.api.dto.PaymentCheckoutContext;
import sn.groupia.payment.domain.model.PaymentRequest;
import sn.groupia.payment.domain.repository.FinancialStore;
import sn.groupia.shared.api.ApiException;

class DirectPaymentClaimEnabledTests {
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final String KEY = "direct-checkout-key-0001";
    private final UUID org = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final FinancialStore store = mock(FinancialStore.class);
    private final DirectPaymentClaimGuard guard = mock(DirectPaymentClaimGuard.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DirectPaymentClaimGuard> guards = mock(ObjectProvider.class);
    private final PaymentCheckoutContext context =
            new PaymentCheckoutContext("DIR-000000000001", "Awa Ndiaye", "+221771234567", null);

    private PaymentRequest prepared() {
        PaymentRequest request = PaymentRequest.create(
                CreatePaymentRequest.forDirectOrder(
                        org, order, BigDecimal.valueOf(10000), BigDecimal.valueOf(300),
                        BigDecimal.ZERO, NOW.plusSeconds(3600)), NOW);
        when(store.request(request.getId())).thenReturn(Optional.of(request));
        return request;
    }

    @Test
    void claimRemainsForbiddenWhenFeatureIsOffEvenWithGuardPresent() {
        PaymentRequest req = prepared();
        var transactions = new PaymentTransactions(
                store, null, Clock.fixed(NOW, ZoneOffset.UTC), null, null, null, guards, false);
        assertThatThrownBy(() -> transactions.claim(
                org, req.getId(), "BICTORYS", "sandbox", KEY, context))
                .isInstanceOf(ApiException.class).hasMessageContaining("pas encore disponible");
        verifyNoInteractions(guards, guard);
        verify(store, never()).lockRequest(any());
    }

    @Test
    void claimRejectsOtherProvidersBeforeAnyFinancialLock() {
        PaymentRequest req = prepared();
        var transactions = new PaymentTransactions(
                store, null, Clock.fixed(NOW, ZoneOffset.UTC), null, null, null, guards, true);
        assertThatThrownBy(() -> transactions.claim(
                org, req.getId(), "ANOTHER_GATEWAY", "other", KEY, context))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(guards, guard);
        verify(store, never()).lockRequest(any());
    }

    @Test
    void guardIsMandatoryEvenIfFeatureFlagIsEnabled() {
        PaymentRequest req = prepared();
        when(guards.getIfAvailable()).thenReturn(null);
        var transactions = new PaymentTransactions(
                store, null, Clock.fixed(NOW, ZoneOffset.UTC), null, null, null, guards, true);
        assertThatThrownBy(() -> transactions.claim(
                org, req.getId(), "BICTORYS", "sandbox", KEY, context))
                .isInstanceOf(ApiException.class);
        verify(store, never()).lockRequest(any());
    }

    @Test
    void canonicalGuardRunsBeforeFinancialRequestLockAndAttemptCreation() {
        PaymentRequest req = prepared();
        when(guards.getIfAvailable()).thenReturn(guard);
        when(guard.authorize(org, order, req.getId(), req.getExpectedAmount(), context))
                .thenReturn(context);
        when(store.lockRequest(req.getId())).thenReturn(req);
        when(store.attempt(req.getId(), KEY)).thenReturn(Optional.empty());
        when(store.attempts(req.getId())).thenReturn(List.of());
        var transactions = new PaymentTransactions(
                store, null, Clock.fixed(NOW, ZoneOffset.UTC), null, null, null, guards, true);
        var claim = transactions.claim(
                org, req.getId(), "BICTORYS", "sandbox", KEY, context);
        assertThat(claim.owned()).isTrue();
        assertThat(claim.instruction().checkout()).isEqualTo(context);
        var orderOfOperations = inOrder(guard, store);
        orderOfOperations.verify(guard).authorize(
                org, order, req.getId(), req.getExpectedAmount(), context);
        orderOfOperations.verify(store).lockRequest(req.getId());
        verify(store).add(any(sn.groupia.payment.domain.model.Payment.class));
    }
}
