package sn.groupia.directsales.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
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
import sn.groupia.organization.api.OrganizationAccess;
import sn.groupia.organization.api.OrganizationPermission;
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

class DirectOrderPreparationTests {
    private static final Instant NOW = Instant.parse("2026-11-03T15:00:00Z");
    private static final Instant CONFIRMED = NOW.minusSeconds(120);
    private final UUID org = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID storefrontId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private OrganizationAccess access;
    private PaymentOperations payments;
    private DirectOrderPreparation subject;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        access = mock(OrganizationAccess.class);
        payments = mock(PaymentOperations.class);
        subject =
                new DirectOrderPreparation(
                        jdbc, access, payments, Clock.fixed(NOW, ZoneOffset.UTC));
        when(access.requireActorPermission(org, OrganizationPermission.MANAGE_FULFILLMENT))
                .thenReturn(actor);
        when(jdbc.query(
                        contains("select s.id from direct_storefronts"),
                        any(RowMapper.class),
                        eq(org),
                        eq(orderId)))
                .thenReturn(List.of(storefrontId));
    }

    private void order(String status, Instant preparingAt) {
        when(jdbc.query(
                        contains("select order_number,status,currency,accepted_total_xof"),
                        any(RowMapper.class),
                        eq(org),
                        eq(orderId),
                        eq(storefrontId)))
                .thenReturn(
                        List.of(
                                new DirectOrderPreparation.Order(
                                        "DIR-000000000001",
                                        status,
                                        "XOF",
                                        10300L,
                                        CONFIRMED,
                                        preparingAt)));
    }

    private void settlement() {
        when(jdbc.query(
                        contains("from direct_order_verified_settlements"),
                        any(RowMapper.class),
                        eq(org),
                        eq(orderId)))
                .thenReturn(
                        List.of(
                                new DirectOrderPreparation.Settlement(
                                        requestId,
                                        paymentId,
                                        BigDecimal.valueOf(10300),
                                        "XOF",
                                        "BICTORYS",
                                        "gateway-123",
                                        CONFIRMED)));
    }

    private void state(PaymentOperationalStatus status, boolean reconciling) {
        PaymentRequestView request = mock(PaymentRequestView.class);
        when(request.id()).thenReturn(requestId);
        when(request.organizationId()).thenReturn(org);
        when(request.target()).thenReturn(PaymentTarget.directOrder(orderId));
        when(request.purpose()).thenReturn(PaymentPurpose.DIRECT_ORDER_INITIAL);
        when(request.acceptanceStatus()).thenReturn(AcceptanceStatus.CLOSED);
        when(request.settledPaymentId()).thenReturn(paymentId);
        when(request.currency()).thenReturn("XOF");
        when(request.expectedAmount()).thenReturn(BigDecimal.valueOf(10300));
        PaymentRequestState answer = new PaymentRequestState(request, status, false, reconciling);
        when(payments.lockRequestState(org, requestId)).thenReturn(answer);
    }

    private PaymentSettlementEvidence proof(boolean reversed) {
        return new PaymentSettlementEvidence(
                org,
                requestId,
                paymentId,
                PaymentTarget.directOrder(orderId),
                PaymentPurpose.DIRECT_ORDER_INITIAL,
                BigDecimal.valueOf(10300),
                "XOF",
                AcceptanceStatus.CLOSED,
                PaymentStatus.SUCCEEDED,
                "BICTORYS",
                "merchant-account",
                "gateway-123",
                "payment-ref",
                CONFIRMED,
                NOW.plusSeconds(3600),
                reversed);
    }

    private void safeFinancialEvidence() {
        order("PAYMENT_CONFIRMED", null);
        settlement();
        state(PaymentOperationalStatus.SETTLED, false);
        when(payments.settlementEvidence(org, requestId)).thenReturn(proof(false));
        when(jdbc.update(startsWith("update direct_orders d"), any(Object[].class))).thenReturn(1);
    }

    @Test
    void onlyVerifiedPaymentAllowsMerchantToStartPreparation() {
        safeFinancialEvidence();

        var result = subject.start(org, orderId);

        assertThat(result.orderNumber()).isEqualTo("DIR-000000000001");
        assertThat(result.status()).isEqualTo("PREPARING");
        assertThat(result.preparingAt()).isEqualTo(NOW);
        verify(access).requireActorPermission(org, OrganizationPermission.MANAGE_FULFILLMENT);
        verify(payments).lockRequestState(org, requestId);
        verify(payments).settlementEvidence(org, requestId);
        verify(jdbc)
                .update(
                        startsWith("update direct_orders d"),
                        eq(Timestamp.from(NOW)),
                        eq(actor),
                        eq(org),
                        eq(orderId),
                        eq(Timestamp.from(CONFIRMED)),
                        eq(requestId),
                        eq(paymentId));
    }

    @Test
    void preparationReplayIsIdempotentAndNeverInitiatesAnotherPayment() {
        order("PREPARING", NOW.minusSeconds(15));

        var result = subject.start(org, orderId);

        assertThat(result.status()).isEqualTo("PREPARING");
        assertThat(result.preparingAt()).isEqualTo(NOW.minusSeconds(15));
        verifyNoInteractions(payments);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void unpaidAndCancelledOrdersCannotBePrepared() {
        order("PENDING_PAYMENT_INTEGRATION", null);

        assertThatThrownBy(() -> subject.start(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("règlement vérifié");
        verifyNoInteractions(payments);

        order("CANCELLED", null);
        assertThatThrownBy(() -> subject.start(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("règlement vérifié");
    }

    @Test
    void missingSettlementReceiptCannotStartPreparation() {
        order("PAYMENT_CONFIRMED", null);

        assertThatThrownBy(() -> subject.start(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("règlement vérifié");
        verifyNoInteractions(payments);
    }

    @Test
    void financialReviewBlocksPreparation() {
        order("PAYMENT_CONFIRMED", null);
        settlement();
        state(PaymentOperationalStatus.REVIEW_REQUIRED, true);

        assertThatThrownBy(() -> subject.start(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("anomalie financière");
        verify(payments, never()).settlementEvidence(any(), any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void reversedPaymentCannotStartPreparation() {
        order("PAYMENT_CONFIRMED", null);
        settlement();
        state(PaymentOperationalStatus.SETTLED, false);
        when(payments.settlementEvidence(org, requestId)).thenReturn(proof(true));

        assertThatThrownBy(() -> subject.start(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("anomalie financière");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void aFailedConditionalUpdateCannotReportPreparationStarted() {
        safeFinancialEvidence();
        when(jdbc.update(startsWith("update direct_orders d"), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> subject.start(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("anomalie financière");
    }

    @Test
    void unauthorizedOrForeignMerchantCannotReadOrModifyTheOrder() {
        doThrow(new ApiException(403, "ACCESS_DENIED", "Interdit"))
                .when(access)
                .requireActorPermission(org, OrganizationPermission.MANAGE_FULFILLMENT);
        assertThatThrownBy(() -> subject.start(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Interdit");
        verifyNoInteractions(jdbc, payments);

        reset(access);
        when(access.requireActorPermission(org, OrganizationPermission.MANAGE_FULFILLMENT))
                .thenReturn(actor);
        reset(jdbc);
        assertThatThrownBy(() -> subject.start(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("introuvable");
        verifyNoInteractions(payments);
    }
}
