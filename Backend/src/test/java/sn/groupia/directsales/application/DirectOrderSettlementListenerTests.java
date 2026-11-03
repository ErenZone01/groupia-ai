package sn.groupia.directsales.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
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
import sn.groupia.payment.api.PaymentOperationalStatus;
import sn.groupia.payment.api.PaymentOperations;
import sn.groupia.payment.api.PaymentPurpose;
import sn.groupia.payment.api.PaymentStatus;
import sn.groupia.payment.api.PaymentTarget;
import sn.groupia.payment.api.dto.PaymentRequestState;
import sn.groupia.payment.api.dto.PaymentRequestView;
import sn.groupia.payment.api.dto.PaymentSettlementEvidence;
import sn.groupia.payment.api.event.PaymentObligationSettled;

class DirectOrderSettlementListenerTests {
    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");

    private final UUID org = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final UUID request = UUID.randomUUID();
    private final UUID payment = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private PaymentOperations payments;
    private DirectOrderSettlementListener listener;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        payments = mock(PaymentOperations.class);
        listener =
                new DirectOrderSettlementListener(jdbc, payments, Clock.fixed(NOW, ZoneOffset.UTC));
        PaymentRequestView view = mock(PaymentRequestView.class);
        when(view.id()).thenReturn(request);
        when(view.organizationId()).thenReturn(org);
        when(view.target()).thenReturn(PaymentTarget.directOrder(order));
        when(view.settledPaymentId()).thenReturn(payment);
        when(view.purpose()).thenReturn(PaymentPurpose.DIRECT_ORDER_INITIAL);
        when(view.acceptanceStatus()).thenReturn(AcceptanceStatus.CLOSED);
        when(view.expectedAmount()).thenReturn(BigDecimal.valueOf(10300));
        when(view.currency()).thenReturn("XOF");
        when(payments.requestState(org, request))
                .thenReturn(
                        new PaymentRequestState(
                                view, PaymentOperationalStatus.SETTLED, false, false));
        when(jdbc.update(startsWith("update direct_orders d"), any(Object[].class)))
                .thenReturn(1);
    }

    private PaymentObligationSettled event() {
        return new PaymentObligationSettled(
                eventId,
                NOW.minusSeconds(30),
                org,
                PaymentTarget.directOrder(order),
                request,
                payment,
                PaymentPurpose.DIRECT_ORDER_INITIAL,
                BigDecimal.valueOf(10300),
                "XOF");
    }

    private PaymentSettlementEvidence proof(boolean reversed) {
        return new PaymentSettlementEvidence(
                org,
                request,
                payment,
                PaymentTarget.directOrder(order),
                PaymentPurpose.DIRECT_ORDER_INITIAL,
                BigDecimal.valueOf(10300),
                "XOF",
                AcceptanceStatus.CLOSED,
                PaymentStatus.SUCCEEDED,
                "BICTORYS",
                "merchant-account",
                "gateway-transaction-123",
                "gateway-reference",
                NOW.minusSeconds(30),
                NOW.plusSeconds(120),
                reversed);
    }

    private void orderRow(long total) throws Exception {
        orderRow(total, "PENDING_PAYMENT_INTEGRATION", null);
    }

    private void orderRow(long total, String status, Instant confirmedAt) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("status")).thenReturn(status);
        when(row.getTimestamp("payment_confirmed_at"))
                .thenReturn(confirmedAt == null ? null : Timestamp.from(confirmedAt));
        when(row.getString("status")).thenReturn(status);
        when(row.getString("currency")).thenReturn("XOF");
        when(row.getLong("accepted_total_xof")).thenReturn(total);
        when(jdbc.query(contains("from direct_orders"), any(RowMapper.class), eq(org), eq(order)))
                .thenAnswer(
                        invocation -> {
                            RowMapper<?> mapper = invocation.getArgument(1);
                            return List.of(mapper.mapRow(row, 0));
                        });
    }

    @Test
    void verifiedEvidenceConfirmsOrderButNeverGrantsFulfillment() throws Exception {
        when(payments.settlementEvidence(org, request)).thenReturn(proof(false));
        orderRow(10300);

        listener.on(event());

        verify(jdbc)
                .update(
                        contains("insert into direct_order_verified_settlements"),
                        any(UUID.class),
                        eq(eventId),
                        eq(org),
                        eq(order),
                        eq(request),
                        eq(payment),
                        eq(BigDecimal.valueOf(10300)),
                        eq("XOF"),
                        eq("BICTORYS"),
                        eq("gateway-transaction-123"),
                        eq(Timestamp.from(NOW.minusSeconds(30))),
                        eq(Timestamp.from(NOW)));
        verify(jdbc)
                .update(
                        startsWith("update direct_orders d"),
                        eq(Timestamp.from(NOW.minusSeconds(30))),
                        eq(org),
                        eq(order),
                        eq(request),
                        eq(payment),
                        eq(Timestamp.from(NOW.minusSeconds(30))));
    }

    @Test
    void ignoresOtherPaymentTargetsWithoutDatabaseCalls() {
        var other =
                new PaymentObligationSettled(
                        eventId,
                        NOW,
                        org,
                        PaymentTarget.subscription(UUID.randomUUID()),
                        request,
                        payment,
                        PaymentPurpose.SUBSCRIPTION_MONTHLY,
                        BigDecimal.valueOf(10000),
                        "XOF");

        listener.on(other);

        verifyNoInteractions(payments, jdbc);
    }

    @Test
    void rejectedReversalAndWrongOrderAmountCannotCreateReceipt() throws Exception {
        when(payments.settlementEvidence(org, request)).thenReturn(proof(true));
        assertThatThrownBy(() -> listener.on(event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evidence mismatch");
        verifyNoInteractions(jdbc);

        when(payments.settlementEvidence(org, request)).thenReturn(proof(false));
        orderRow(10400);
        assertThatThrownBy(() -> listener.on(event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("amount/status mismatch");
        verify(jdbc, never())
                .update(
                        contains("insert into direct_order_verified_settlements"),
                        any(Object[].class));
    }

    @Test
    void confirmedPaymentReplayMatchesExistingImmutableReceipt() throws Exception {
        when(payments.settlementEvidence(org, request)).thenReturn(proof(false));
        orderRow(10300, "PAYMENT_CONFIRMED", NOW.minusSeconds(30));
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("payment_request_id", UUID.class)).thenReturn(request);
        when(row.getObject("payment_id", UUID.class)).thenReturn(payment);
        when(row.getBigDecimal("amount_xof")).thenReturn(BigDecimal.valueOf(10300));
        when(row.getString("provider")).thenReturn("BICTORYS");
        when(row.getString("provider_transaction_id")).thenReturn("gateway-transaction-123");
        when(jdbc.query(
                        contains("from direct_order_verified_settlements"),
                        any(RowMapper.class),
                        eq(org),
                        eq(order)))
                .thenAnswer(
                        invocation -> {
                            RowMapper<?> mapper = invocation.getArgument(1);
                            return List.of(mapper.mapRow(row, 0));
                        });

        listener.on(event());

        verify(jdbc, never())
                .update(
                        contains("insert into direct_order_verified_settlements"),
                        any(Object[].class));
    }

    @Test
    void inconsistentReplayIsNeverSilentlyAccepted() throws Exception {
        when(payments.settlementEvidence(org, request)).thenReturn(proof(false));
        orderRow(10300);
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("payment_request_id", UUID.class)).thenReturn(request);
        when(row.getObject("payment_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(row.getBigDecimal("amount_xof")).thenReturn(BigDecimal.valueOf(10300));
        when(row.getString("provider")).thenReturn("BICTORYS");
        when(row.getString("provider_transaction_id")).thenReturn("gateway-transaction-123");
        when(jdbc.query(
                        contains("from direct_order_verified_settlements"),
                        any(RowMapper.class),
                        eq(org),
                        eq(order)))
                .thenAnswer(
                        invocation -> {
                            RowMapper<?> mapper = invocation.getArgument(1);
                            return List.of(mapper.mapRow(row, 0));
                        });

        assertThatThrownBy(() -> listener.on(event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Contradictory");
    }


    @Test
    void oldVerifiedReceiptCanSafelyFinishTheFinancialTransition() throws Exception {
        when(payments.settlementEvidence(org, request)).thenReturn(proof(false));
        orderRow(10300);
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("payment_request_id", UUID.class)).thenReturn(request);
        when(row.getObject("payment_id", UUID.class)).thenReturn(payment);
        when(row.getBigDecimal("amount_xof")).thenReturn(BigDecimal.valueOf(10300));
        when(row.getString("provider")).thenReturn("BICTORYS");
        when(row.getString("provider_transaction_id")).thenReturn("gateway-transaction-123");
        when(jdbc.query(
                        contains("from direct_order_verified_settlements"),
                        any(RowMapper.class),
                        eq(org),
                        eq(order)))
                .thenAnswer(
                        invocation -> {
                            RowMapper<?> mapper = invocation.getArgument(1);
                            return List.of(mapper.mapRow(row, 0));
                        });

        listener.on(event());

        verify(jdbc)
                .update(
                        startsWith("update direct_orders d"),
                        eq(Timestamp.from(NOW.minusSeconds(30))),
                        eq(org),
                        eq(order),
                        eq(request),
                        eq(payment),
                        eq(Timestamp.from(NOW.minusSeconds(30))));
        verify(jdbc, never())
                .update(
                        contains("insert into direct_order_verified_settlements"),
                        any(Object[].class));
    }

    @Test
    void unsettledOrFlaggedPaymentCannotChangeDirectOrder() throws Exception {
        when(payments.settlementEvidence(org, request)).thenReturn(proof(false));
        PaymentRequestView view = payments.requestState(org, request).request();
        when(payments.requestState(org, request))
                .thenReturn(
                        new PaymentRequestState(
                                view, PaymentOperationalStatus.REVIEW_REQUIRED, false, true));

        assertThatThrownBy(() -> listener.on(event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unresolved financial risk");
        verifyNoInteractions(jdbc);
    }

    @Test
    void cancelledOrderOrMissingReceiptCannotBeConfirmed() throws Exception {
        when(payments.settlementEvidence(org, request)).thenReturn(proof(false));
        orderRow(10300, "CANCELLED", null);

        assertThatThrownBy(() -> listener.on(event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("amount/status mismatch");
        verify(jdbc, never())
                .update(startsWith("update direct_orders d"), any(Object[].class));
    }
}
