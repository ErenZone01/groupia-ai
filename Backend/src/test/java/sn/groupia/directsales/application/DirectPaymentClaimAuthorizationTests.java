package sn.groupia.directsales.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import sn.groupia.payment.api.AcceptanceStatus;
import sn.groupia.payment.api.PaymentPurpose;
import sn.groupia.payment.api.PaymentTarget;
import sn.groupia.payment.api.dto.PaymentCheckoutContext;
import sn.groupia.payment.api.dto.PaymentRequestView;
import sn.groupia.shared.api.ApiException;

class DirectPaymentClaimAuthorizationTests {
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final String TOKEN_HASH = "a".repeat(64);
    private final UUID org = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final DirectPaymentObligations obligations = mock(DirectPaymentObligations.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private DirectPaymentClaimAuthorization guard;

    @BeforeEach
    void setUp() {
        guard =
                new DirectPaymentClaimAuthorization(
                        obligations, jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void eligible() {
        PaymentRequestView payment = mock(PaymentRequestView.class);
        when(payment.id()).thenReturn(requestId);
        when(payment.organizationId()).thenReturn(org);
        when(payment.target()).thenReturn(PaymentTarget.directOrder(order));
        when(payment.purpose()).thenReturn(PaymentPurpose.DIRECT_ORDER_INITIAL);
        when(payment.acceptanceStatus()).thenReturn(AcceptanceStatus.OPEN);
        when(payment.expectedAmount()).thenReturn(BigDecimal.valueOf(10300));
        when(payment.currency()).thenReturn("XOF");
        when(payment.expiresAt()).thenReturn(NOW.plusSeconds(3600));
        when(obligations.prepare(org, order)).thenReturn(payment);
        when(jdbc.query(
                        contains("from direct_orders d"),
                        any(RowMapper.class),
                        eq(org),
                        eq(order),
                        eq(TOKEN_HASH)))
                .thenReturn(
                        List.of(
                                new DirectPaymentClaimAuthorization.Contact(
                                        "DIR-000000000001",
                                        "Awa",
                                        "Ndiaye",
                                        "+221771234567",
                                        null)));
    }

    private PaymentCheckoutContext context() {
        return new PaymentCheckoutContext("DIR-000000000001", "Awa Ndiaye", "+221771234567", null);
    }

    @Test
    void permitsOnlyCanonicalCustomerContextAfterRevalidatingUnderLocks() {
        eligible();
        assertThat(
                        guard.authorize(
                                org,
                                order,
                                requestId,
                                BigDecimal.valueOf(10300),
                                context(),
                                TOKEN_HASH))
                .isEqualTo(context());
        verify(obligations).prepare(org, order);
        verify(jdbc)
                .query(
                        contains("from direct_orders d"),
                        any(RowMapper.class),
                        eq(org),
                        eq(order),
                        eq(TOKEN_HASH));
    }

    @Test
    void rejectsForgedCustomerIdentityBeforeClaim() {
        eligible();
        PaymentCheckoutContext forged =
                new PaymentCheckoutContext(
                        "DIR-000000000001", "Autre Personne", "+221771234567", null);
        assertThatThrownBy(
                        () ->
                                guard.authorize(
                                        org,
                                        order,
                                        requestId,
                                        BigDecimal.valueOf(10300),
                                        forged,
                                        TOKEN_HASH))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("conditions de paiement");
    }

    @Test
    void refusesMismatchedRequestAndTotalOrMissingFinancialSnapshot() {
        eligible();
        assertThatThrownBy(
                        () ->
                                guard.authorize(
                                        org,
                                        order,
                                        UUID.randomUUID(),
                                        BigDecimal.valueOf(10300),
                                        context(),
                                        TOKEN_HASH))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(
                        () ->
                                guard.authorize(
                                        org,
                                        order,
                                        requestId,
                                        BigDecimal.valueOf(10301),
                                        context(),
                                        TOKEN_HASH))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(
                        () -> guard.authorize(org, order, requestId, null, context(), TOKEN_HASH))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsRevokedTokenAtTheLockedOrderBoundary() {
        eligible();
        assertThatThrownBy(
                        () ->
                                guard.authorize(
                                        org,
                                        order,
                                        requestId,
                                        BigDecimal.valueOf(10300),
                                        context(),
                                        "short"))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(obligations, jdbc);
    }

    @Test
    void refusesClosedOrExpiredRequestsWithoutCreatingAnAttempt() {
        eligible();
        PaymentRequestView closed = mock(PaymentRequestView.class);
        when(closed.id()).thenReturn(requestId);
        when(closed.organizationId()).thenReturn(org);
        when(closed.target()).thenReturn(PaymentTarget.directOrder(order));
        when(closed.purpose()).thenReturn(PaymentPurpose.DIRECT_ORDER_INITIAL);
        when(closed.acceptanceStatus()).thenReturn(AcceptanceStatus.CLOSED);
        when(obligations.prepare(org, order)).thenReturn(closed);
        assertThatThrownBy(
                        () ->
                                guard.authorize(
                                        org,
                                        order,
                                        requestId,
                                        BigDecimal.valueOf(10300),
                                        context(),
                                        TOKEN_HASH))
                .isInstanceOf(ApiException.class);
        verifyNoMoreInteractions(jdbc);
    }
}
