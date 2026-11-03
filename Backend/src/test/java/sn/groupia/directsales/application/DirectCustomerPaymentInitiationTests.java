package sn.groupia.directsales.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import sn.groupia.payment.api.PaymentOperations;
import sn.groupia.payment.api.PaymentStatus;
import sn.groupia.payment.api.dto.PaymentAttemptView;
import sn.groupia.payment.api.dto.PaymentCheckoutContext;
import sn.groupia.shared.api.ApiException;

class DirectCustomerPaymentInitiationTests {
    private static final String TOKEN =
            Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private static final String KEY = "direct-checkout-key-0001";
    private final UUID organization = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PaymentOperations payments = mock(PaymentOperations.class);

    private void order(String status, UUID obligation) {
        when(jdbc.query(
                contains("where d.recovery_token_hash=?"),
                any(RowMapper.class), anyString()))
                .thenReturn(List.of(new DirectCustomerPaymentInitiation.Order(
                        organization, orderId, obligation, "DIR-000000000001",
                        status, "XOF", 10300L, "Awa", "Ndiaye", "+221771234567", null)));
    }

    @Test
    void disabledSwitchCannotCreateEvenOneAttempt() {
        var subject = new DirectCustomerPaymentInitiation(jdbc, payments, false);
        assertThatThrownBy(() -> subject.initiate(TOKEN, KEY))
                .isInstanceOf(ApiException.class).hasMessageContaining("pas encore disponible");
        verifyNoInteractions(jdbc, payments);
    }

    @Test
    void tokenSelectsServerOwnedRequestAndContactWithoutBrowserPrice() {
        order("PENDING_PAYMENT_INTEGRATION", requestId);
        var expected = new PaymentAttemptView(
                UUID.randomUUID(), PaymentStatus.PENDING,
                BigDecimal.valueOf(10300), "XOF", false, null, null);
        when(payments.initiateDirect(
                eq(organization), eq(requestId), eq(KEY),
                eq(DirectAccessTokens.hashForLookup(TOKEN)), any()))
                .thenReturn(expected);
        var subject = new DirectCustomerPaymentInitiation(jdbc, payments, true);
        assertThat(subject.initiate(TOKEN, KEY)).isEqualTo(expected);
        verify(jdbc).query(
                contains("where d.recovery_token_hash=?"),
                any(RowMapper.class), eq(DirectAccessTokens.hashForLookup(TOKEN)));
        verify(payments).initiateDirect(
                organization, requestId, KEY, DirectAccessTokens.hashForLookup(TOKEN),
                new PaymentCheckoutContext(
                        "DIR-000000000001", "Awa Ndiaye", "+221771234567", null));
        verifyNoMoreInteractions(payments);
    }

    @Test
    void invalidTokenCannotEnumerateOrders() {
        var subject = new DirectCustomerPaymentInitiation(jdbc, payments, true);
        assertThatThrownBy(() -> subject.initiate("DIR-000000000001", KEY))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(jdbc, payments);
    }

    @Test
    void expiredCancelledOrUnpreparedOrderNeverReachesProvider() {
        var subject = new DirectCustomerPaymentInitiation(jdbc, payments, true);
        order("CANCELLED", requestId);
        assertThatThrownBy(() -> subject.initiate(TOKEN, KEY))
                .isInstanceOf(ApiException.class);
        order("PENDING_PAYMENT_INTEGRATION", null);
        assertThatThrownBy(() -> subject.initiate(TOKEN, KEY))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(payments);
    }

    @Test
    void enforcesIdempotencyKeyBeforeAnyOrderLookup() {
        var subject = new DirectCustomerPaymentInitiation(jdbc, payments, true);
        assertThatThrownBy(() -> subject.initiate(TOKEN, "short"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Clé");
        verifyNoInteractions(jdbc, payments);
    }
}
