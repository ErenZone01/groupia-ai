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

class DirectOrderReadinessTests {
    private static final Instant NOW = Instant.parse("2026-11-03T16:00:00Z");
    private static final Instant CONFIRMED = NOW.minusSeconds(120);
    private static final Instant PREPARING = NOW.minusSeconds(60);

    private final UUID org = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private OrganizationAccess access;
    private PaymentOperations payments;
    private DirectOrderReadiness subject;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        access = mock(OrganizationAccess.class);
        payments = mock(PaymentOperations.class);
        subject =
                new DirectOrderReadiness(
                        jdbc, access, payments, Clock.fixed(NOW, ZoneOffset.UTC));
        when(access.requireActorPermission(org, OrganizationPermission.MANAGE_FULFILLMENT))
                .thenReturn(actor);
        when(jdbc.query(
                        contains("select s.id from direct_storefronts"),
                        any(RowMapper.class),
                        eq(org),
                        eq(orderId)))
                .thenReturn(List.of(storeId));
    }

    private void order(String status, String method, Instant ready) {
        when(jdbc.query(
                        contains("select order_number,status,fulfillment_method"),
                        any(RowMapper.class),
                        eq(org),
                        eq(orderId),
                        eq(storeId)))
                .thenReturn(
                        List.of(
                                new DirectOrderReadiness.Order(
                                        "DIR-000000000001",
                                        status,
                                        method,
                                        "XOF",
                                        10300L,
                                        CONFIRMED,
                                        PREPARING,
                                        ready)));
    }

    private void settlement(BigDecimal amount) {
        when(jdbc.query(
                        contains("from direct_order_verified_settlements"),
                        any(RowMapper.class),
                        eq(org),
                        eq(orderId)))
                .thenReturn(
                        List.of(
                                new DirectOrderReadiness.Settlement(
                                        requestId,
                                        paymentId,
                                        amount,
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
                "payment-reference",
                CONFIRMED,
                NOW.plusSeconds(3600),
                reversed);
    }

    private void safe(String method) {
        order("PREPARING", method, null);
        settlement(BigDecimal.valueOf(10300));
        state(PaymentOperationalStatus.SETTLED, false);
        when(payments.settlementEvidence(org, requestId)).thenReturn(proof(false));
        when(jdbc.update(startsWith("update direct_orders d"), any(Object[].class))).thenReturn(1);
    }

    @Test
    void pickupOrderBecomesReadyWithoutReleasingGoods() {
        safe("PICKUP");

        var receipt = subject.markReady(org, orderId);

        assertThat(receipt.status()).isEqualTo("READY_FOR_PICKUP");
        assertThat(receipt.fulfillmentMethod()).isEqualTo("PICKUP");
        assertThat(receipt.readyAt()).isEqualTo(NOW);
        verify(access).requireActorPermission(org, OrganizationPermission.MANAGE_FULFILLMENT);
        verify(payments).lockRequestState(org, requestId);
        verify(payments).settlementEvidence(org, requestId);
        verify(jdbc)
                .update(
                        startsWith("update direct_orders d"),
                        eq("READY_FOR_PICKUP"),
                        eq(Timestamp.from(NOW)),
                        eq(actor),
                        eq(org),
                        eq(orderId),
                        eq("PICKUP"),
                        eq(Timestamp.from(CONFIRMED)),
                        eq(Timestamp.from(NOW)),
                        eq(requestId),
                        eq(paymentId),
                        eq(Timestamp.from(CONFIRMED)));
    }

    @Test
    void deliveryOrderBecomesReadyForDispatchWithoutDispatching() {
        safe("DELIVERY");

        var receipt = subject.markReady(org, orderId);

        assertThat(receipt.status()).isEqualTo("READY_FOR_DISPATCH");
        assertThat(receipt.fulfillmentMethod()).isEqualTo("DELIVERY");
        verify(jdbc)
                .update(
                        startsWith("update direct_orders d"),
                        eq("READY_FOR_DISPATCH"),
                        eq(Timestamp.from(NOW)),
                        eq(actor),
                        eq(org),
                        eq(orderId),
                        eq("DELIVERY"),
                        eq(Timestamp.from(CONFIRMED)),
                        eq(Timestamp.from(NOW)),
                        eq(requestId),
                        eq(paymentId),
                        eq(Timestamp.from(CONFIRMED)));
    }

    @Test
    void retryingReadyCommandDoesNotWriteOrCallPayments() {
        order("READY_FOR_PICKUP", "PICKUP", PREPARING);

        var receipt = subject.markReady(org, orderId);

        assertThat(receipt.status()).isEqualTo("READY_FOR_PICKUP");
        assertThat(receipt.readyAt()).isEqualTo(PREPARING);
        verifyNoInteractions(payments);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void unpaidCancelledAndNotYetPreparingOrdersAreRejected() {
        for (String state :
                List.of("PENDING_PAYMENT_INTEGRATION", "CANCELLED", "PAYMENT_CONFIRMED")) {
            order(state, "PICKUP", null);
            assertThatThrownBy(() -> subject.markReady(org, orderId))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("paiement vérifié");
        }
        verifyNoInteractions(payments);
    }

    @Test
    void missingReceiptOrUnrecognizedFulfillmentMethodIsRejected() {
        order("PREPARING", "PICKUP", null);
        assertThatThrownBy(() -> subject.markReady(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("paiement vérifié");

        order("PREPARING", "OTHER", null);
        assertThatThrownBy(() -> subject.markReady(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("paiement vérifié");
        verifyNoInteractions(payments);
    }

    @Test
    void reconciliationOrProviderReversalBlocksReadiness() {
        order("PREPARING", "DELIVERY", null);
        settlement(BigDecimal.valueOf(10300));
        state(PaymentOperationalStatus.REVIEW_REQUIRED, true);

        assertThatThrownBy(() -> subject.markReady(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("sans anomalie");
        verify(payments, never()).settlementEvidence(any(), any());

        state(PaymentOperationalStatus.SETTLED, false);
        when(payments.settlementEvidence(org, requestId)).thenReturn(proof(true));
        assertThatThrownBy(() -> subject.markReady(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("sans anomalie");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void mismatchedImmutableAmountOrFailedWriteCannotClaimReady() {
        order("PREPARING", "PICKUP", null);
        settlement(BigDecimal.valueOf(10400));
        state(PaymentOperationalStatus.SETTLED, false);
        when(payments.settlementEvidence(org, requestId)).thenReturn(proof(false));
        assertThatThrownBy(() -> subject.markReady(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("sans anomalie");

        settlement(BigDecimal.valueOf(10300));
        when(jdbc.update(startsWith("update direct_orders d"), any(Object[].class))).thenReturn(0);
        assertThatThrownBy(() -> subject.markReady(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("sans anomalie");
    }

    @Test
    void unauthorizedAndCrossTenantRequestNeverReadsOrMutatesOrder() {
        doThrow(new ApiException(403, "ACCESS_DENIED", "Interdit"))
                .when(access)
                .requireActorPermission(org, OrganizationPermission.MANAGE_FULFILLMENT);
        assertThatThrownBy(() -> subject.markReady(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Interdit");
        verifyNoInteractions(jdbc, payments);

        reset(access);
        when(access.requireActorPermission(org, OrganizationPermission.MANAGE_FULFILLMENT))
                .thenReturn(actor);
        reset(jdbc);
        assertThatThrownBy(() -> subject.markReady(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("introuvable");
    }
}
