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

class DirectOrderFulfillmentTests {
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
    private DirectOrderFulfillment subject;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        access = mock(OrganizationAccess.class);
        payments = mock(PaymentOperations.class);
        subject =
                new DirectOrderFulfillment(jdbc, access, payments, Clock.fixed(NOW, ZoneOffset.UTC));
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
                                new DirectOrderFulfillment.Order(
                                        "DIR-000000000001",
                                        status,
                                        method,
                                        "XOF",
                                        10300L,
                                        CONFIRMED,
                                        PREPARING,
                                        ready,
                                        "DISPATCHED".equals(status) ? NOW.minusSeconds(5) : null)));
    }

    private void settlement(BigDecimal amount) {
        when(jdbc.query(
                        contains("from direct_order_verified_settlements"),
                        any(RowMapper.class),
                        eq(org),
                        eq(orderId)))
                .thenReturn(
                        List.of(
                                new DirectOrderFulfillment.Settlement(
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

    private void safe(String status, String method) {
        order(status, method, NOW.minusSeconds(30));
        settlement(BigDecimal.valueOf(10300));
        state(PaymentOperationalStatus.SETTLED, false);
        when(payments.settlementEvidence(org, requestId)).thenReturn(proof(false));
        when(jdbc.update(startsWith("update direct_orders set"), any(Object[].class))).thenReturn(1);
    }

    @Test
    void recordsDispatchDeliveryAndPickupOnlyFromTheMatchingPhysicalStep() {
        for (var action : DirectOrderFulfillment.Action.values()) {
            safe(action.previousStatus, action.method);
            var receipt = subject.fulfill(org, orderId, action, true);
            assertThat(receipt.status()).isEqualTo(action.nextStatus);
            assertThat(receipt.occurredAt()).isEqualTo(NOW);
        }
        verify(jdbc, times(3)).update(startsWith("insert into direct_order_fulfillment_events"), any(Object[].class));
    }

    @Test
    void neverAcceptsMissingConfirmationOrSkipsPreparationAndDispatch() {
        assertThatThrownBy(() -> subject.fulfill(org, orderId, DirectOrderFulfillment.Action.PICKUP, false))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(jdbc, payments);
        for (String status : List.of("PENDING_PAYMENT_INTEGRATION", "PAYMENT_CONFIRMED", "PREPARING", "CANCELLED")) {
            order(status, "PICKUP", NOW.minusSeconds(30));
            assertThatThrownBy(() -> subject.fulfill(org, orderId, DirectOrderFulfillment.Action.PICKUP, true))
                    .isInstanceOf(ApiException.class);
        }
        safe("READY_FOR_DISPATCH", "DELIVERY");
        assertThatThrownBy(() -> subject.fulfill(org, orderId, DirectOrderFulfillment.Action.DELIVER, true))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> subject.fulfill(org, orderId, DirectOrderFulfillment.Action.PICKUP, true))
                .isInstanceOf(ApiException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void reviewReversalMissingAndMismatchedEvidenceBlockHandoff() {
        safe("READY_FOR_PICKUP", "PICKUP");
        state(PaymentOperationalStatus.REVIEW_REQUIRED, true);
        assertBlocked();
        state(PaymentOperationalStatus.SETTLED, false);
        when(payments.settlementEvidence(org, requestId)).thenReturn(proof(true));
        assertBlocked();
        when(payments.settlementEvidence(org, requestId)).thenReturn(null);
        assertBlocked();
        when(payments.settlementEvidence(org, requestId)).thenReturn(proof(false));
        settlement(BigDecimal.valueOf(10299));
        assertBlocked();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void permissionAndTenantAreCheckedBeforeAReplay() {
        doThrow(new ApiException(403, "ACCESS_DENIED", "Interdit"))
                .when(access).requireActorPermission(org, OrganizationPermission.MANAGE_FULFILLMENT);
        assertBlocked();
        verifyNoInteractions(jdbc, payments);
        reset(access, jdbc);
        assertBlocked();
        verifyNoInteractions(payments);
    }

    @Test
    void retryReturnsOriginalEventWithoutWritingAgainOrRecheckingPastPayment() {
        order("DELIVERED", "DELIVERY", NOW.minusSeconds(30));
        var receipt = new DirectOrderFulfillment.Receipt("DIR-000000000001", "DISPATCHED", "DELIVERY", NOW.minusSeconds(10));
        when(jdbc.query(contains("from direct_order_fulfillment_events"), any(RowMapper.class), eq(org), eq(orderId), eq("DISPATCH")))
                .thenReturn(List.of(receipt));
        assertThat(subject.fulfill(org, orderId, DirectOrderFulfillment.Action.DISPATCH, true)).isEqualTo(receipt);
        verifyNoInteractions(payments);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void aLostCompareAndSetCannotCreateAFulfillmentEvent() {
        safe("READY_FOR_PICKUP", "PICKUP");
        when(jdbc.update(startsWith("update direct_orders set"), any(Object[].class))).thenReturn(0);
        assertBlocked();
        verify(jdbc, never()).update(startsWith("insert into direct_order_fulfillment_events"), any(Object[].class));
    }

    private void assertBlocked() {
        assertThatThrownBy(() -> subject.fulfill(org, orderId, DirectOrderFulfillment.Action.PICKUP, true))
                .isInstanceOf(ApiException.class);
    }
}
