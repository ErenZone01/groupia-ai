package sn.groupia.directsales.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import sn.groupia.payment.api.AcceptanceStatus;
import sn.groupia.payment.api.PaymentOperationalStatus;
import sn.groupia.payment.api.PaymentOperations;
import sn.groupia.payment.api.PaymentPurpose;
import sn.groupia.payment.api.PaymentStatus;
import sn.groupia.payment.api.PaymentTarget;
import sn.groupia.payment.api.dto.PaymentRequestState;
import sn.groupia.payment.api.dto.PaymentRequestView;
import sn.groupia.payment.api.dto.PaymentSettlementEvidence;
import sn.groupia.shared.api.ApiException;

class DirectCustomerPaymentStatusTests {
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final String TOKEN =
            Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private final UUID organization = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private PaymentOperations payments;
    private DirectCustomerPaymentStatus subject;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        payments = mock(PaymentOperations.class);
        subject = new DirectCustomerPaymentStatus(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC), payments);
    }

    private void snapshot(String orderStatus, UUID request, UUID receipt, Long accepted) {
        when(jdbc.query(
                        contains("where d.recovery_token_hash=?"),
                        any(RowMapper.class), anyString()))
                .thenReturn(List.of(new DirectCustomerPaymentStatus.Snapshot(
                        organization, order, "DIR-000000000001", orderStatus, "XOF",
                        accepted, request,
                        request == null ? null : receipt == null ? "OPEN" : "CLOSED",
                        accepted == null ? null : BigDecimal.valueOf(accepted),
                        NOW.plusSeconds(3600), receipt,
                        receipt == null ? null : BigDecimal.valueOf(10300),
                        receipt == null ? null : "BICTORYS",
                        receipt == null ? null : "gateway-transaction",
                        receipt == null ? null : NOW.minusSeconds(20),
                        receipt == null ? null : NOW.minusSeconds(20))));
    }

    private void state(PaymentOperationalStatus operational, UUID settled, boolean reconciliation) {
        PaymentRequestView view = mock(PaymentRequestView.class);
        when(view.id()).thenReturn(requestId);
        when(view.organizationId()).thenReturn(organization);
        when(view.target()).thenReturn(PaymentTarget.directOrder(order));
        when(view.purpose()).thenReturn(PaymentPurpose.DIRECT_ORDER_INITIAL);
        when(view.currency()).thenReturn("XOF");
        when(view.expectedAmount()).thenReturn(BigDecimal.valueOf(10300));
        when(view.acceptanceStatus())
                .thenReturn(settled == null ? AcceptanceStatus.OPEN : AcceptanceStatus.CLOSED);
        when(view.settledPaymentId()).thenReturn(settled);
        when(view.expiresAt()).thenReturn(NOW.plusSeconds(3600));
        when(payments.requestState(organization, requestId))
                .thenReturn(new PaymentRequestState(view, operational, false, reconciliation));
    }

    private PaymentSettlementEvidence proof(boolean reversed) {
        return new PaymentSettlementEvidence(
                organization, requestId, paymentId, PaymentTarget.directOrder(order),
                PaymentPurpose.DIRECT_ORDER_INITIAL, BigDecimal.valueOf(10300), "XOF",
                AcceptanceStatus.CLOSED, PaymentStatus.SUCCEEDED, "BICTORYS", "sandbox",
                "gateway-transaction", "ref", NOW.minusSeconds(20),
                NOW.plusSeconds(3600), reversed);
    }

    @Test
    void preparedObligationRemainsNotPayableWithoutVerifiedSettlement() {
        snapshot("PENDING_PAYMENT_INTEGRATION", requestId, null, 10300L);
        state(PaymentOperationalStatus.PENDING, null, false);
        var result = subject.lookup(TOKEN);
        assertThat(result.orderNumber()).isEqualTo("DIR-000000000001");
        assertThat(result.financialStatus()).isEqualTo("PREPARED");
        assertThat(result.preparationStatus()).isEqualTo("PREPARED");
        assertThat(result.paymentAvailable()).isFalse();
        verify(jdbc).query(contains("where d.recovery_token_hash=?"),
                any(RowMapper.class), eq(DirectAccessTokens.hashForCreate(TOKEN)));
        verify(payments, never()).settlementEvidence(any(), any());
    }

    @Test
    void historicalOrderHasNoPaymentRequest() {
        snapshot("PENDING_PAYMENT_INTEGRATION", null, null, null);
        assertThat(subject.lookup(TOKEN).financialStatus()).isEqualTo("NOT_PREPARED");
        verifyNoInteractions(payments);
    }

    @Test
    void settledButNotRecordedIsOnlyProcessing() {
        snapshot("PENDING_PAYMENT_INTEGRATION", requestId, null, 10300L);
        state(PaymentOperationalStatus.SETTLED, paymentId, false);
        assertThat(subject.lookup(TOKEN).financialStatus()).isEqualTo("SETTLEMENT_PROCESSING");
        verify(payments, never()).settlementEvidence(any(), any());
    }

    @Test
    void confirmedOrderRequiresMatchingCanonicalProviderProof() {
        snapshot("PAYMENT_CONFIRMED", requestId, paymentId, 10300L);
        state(PaymentOperationalStatus.SETTLED, paymentId, false);
        when(payments.settlementEvidence(organization, requestId)).thenReturn(proof(false));

        var result = subject.lookup(TOKEN);
        assertThat(result.financialStatus()).isEqualTo("SETTLEMENT_VERIFIED");
        assertThat(result.paymentAvailable()).isFalse();
        assertThat(result.preparationStatus()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void reversalAndReconciliationFailClosedEvenAfterConfirmation() {
        snapshot("READY_FOR_PICKUP", requestId, paymentId, 10300L);
        state(PaymentOperationalStatus.SETTLED, paymentId, false);
        when(payments.settlementEvidence(organization, requestId)).thenReturn(proof(true));
        assertThat(subject.lookup(TOKEN).financialStatus()).isEqualTo("REVIEW_REQUIRED");

        state(PaymentOperationalStatus.REVIEW_REQUIRED, paymentId, true);
        assertThat(subject.lookup(TOKEN).financialStatus()).isEqualTo("REVIEW_REQUIRED");
        verify(payments, times(1)).settlementEvidence(organization, requestId);
    }

    @Test
    void forgedAmountOrUnconfirmedOrderIsNeverReportedAsPaid() {
        snapshot("PENDING_PAYMENT_INTEGRATION", requestId, paymentId, 10300L);
        state(PaymentOperationalStatus.SETTLED, paymentId, false);
        when(payments.settlementEvidence(organization, requestId)).thenReturn(proof(false));
        assertThat(subject.lookup(TOKEN).financialStatus()).isEqualTo("REVIEW_REQUIRED");

        snapshot("PAYMENT_CONFIRMED", requestId, paymentId, 10301L);
        assertThat(subject.lookup(TOKEN).financialStatus()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void invalidOrUnknownTokenNeverEnumeratesOtherOrders() {
        assertThatThrownBy(() -> subject.lookup("DIR-000000000001"))
                .isInstanceOf(ApiException.class).hasMessageContaining("introuvable");
        assertThatThrownBy(() -> subject.lookup(null)).isInstanceOf(ApiException.class);
        verifyNoInteractions(jdbc, payments);

        assertThatThrownBy(() -> subject.lookup(TOKEN))
                .isInstanceOf(ApiException.class).hasMessageContaining("introuvable");
    }
}
