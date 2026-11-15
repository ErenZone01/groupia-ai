package sn.groupia.directsales.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.*;
import sn.groupia.organization.api.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.shared.api.ApiException;

class DirectOrderFulfillmentTests {
    final UUID org = UUID.randomUUID(),
            order = UUID.randomUUID(),
            requestId = UUID.randomUUID(),
            paymentId = UUID.randomUUID();
    final Instant now = Instant.parse("2026-11-08T00:00:00Z"), confirmed = now.minusSeconds(100);
    final BigDecimal amount = new BigDecimal("20000");
    JdbcTemplate jdbc;
    OrganizationAccess access;
    PaymentOperations payments;
    DirectOrderFulfillment service;
    PaymentRequestView request;
    PaymentSettlementEvidence proof;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        access = mock(OrganizationAccess.class);
        payments = mock(PaymentOperations.class);
        service =
                new DirectOrderFulfillment(
                        jdbc, access, payments, Clock.fixed(now, ZoneOffset.UTC));
        when(access.requireActorPermission(org, OrganizationPermission.MANAGE_FULFILLMENT))
                .thenReturn(UUID.randomUUID());
        when(jdbc.query(contains("select s.id"), any(RowMapper.class), eq(org), eq(order)))
                .thenReturn(List.of(UUID.randomUUID()));
        when(jdbc.query(
                        contains("idempotency_key=?"),
                        any(RowMapper.class),
                        eq(org),
                        eq(order),
                        anyString()))
                .thenReturn(List.of());
        request = mock(PaymentRequestView.class);
        proof = mock(PaymentSettlementEvidence.class);
        when(request.id()).thenReturn(requestId);
        when(request.organizationId()).thenReturn(org);
        when(request.target()).thenReturn(PaymentTarget.directOrder(order));
        when(request.purpose()).thenReturn(PaymentPurpose.DIRECT_ORDER_INITIAL);
        when(request.acceptanceStatus()).thenReturn(AcceptanceStatus.CLOSED);
        when(request.settledPaymentId()).thenReturn(paymentId);
        when(request.currency()).thenReturn("XOF");
        when(request.expectedAmount()).thenReturn(amount);
        when(proof.organizationId()).thenReturn(org);
        when(proof.paymentRequestId()).thenReturn(requestId);
        when(proof.paymentId()).thenReturn(paymentId);
        when(proof.target()).thenReturn(PaymentTarget.directOrder(order));
        when(proof.purpose()).thenReturn(PaymentPurpose.DIRECT_ORDER_INITIAL);
        when(proof.acceptanceStatus()).thenReturn(AcceptanceStatus.CLOSED);
        when(proof.status()).thenReturn(PaymentStatus.SUCCEEDED);
        when(proof.amount()).thenReturn(amount);
        when(proof.currency()).thenReturn("XOF");
        when(proof.confirmedAt()).thenReturn(confirmed);
        when(proof.provider()).thenReturn("SASPAY");
        when(proof.providerTransactionId()).thenReturn("verified-transaction");
        when(payments.lockRequestState(org, requestId))
                .thenReturn(
                        new PaymentRequestState(
                                request, PaymentOperationalStatus.SETTLED, false, false));
        when(payments.settlementEvidence(org, requestId)).thenReturn(proof);
        when(jdbc.queryForList(
                        contains("from direct_order_verified_settlements"), eq(org), eq(order)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "payment_request_id",
                                        requestId,
                                        "payment_id",
                                        paymentId,
                                        "amount_xof",
                                        amount,
                                        "currency",
                                        "XOF",
                                        "provider",
                                        "SASPAY",
                                        "provider_transaction_id",
                                        "verified-transaction",
                                        "confirmed_at",
                                        Timestamp.from(confirmed))));
        when(jdbc.update(startsWith("update direct_orders set"), any(Object[].class)))
                .thenReturn(1);
    }

    private void order(String status, String method) {
        when(jdbc.query(
                        startsWith("select status,fulfillment_method"),
                        any(RowMapper.class),
                        eq(org),
                        eq(order)))
                .thenReturn(
                        List.of(
                                new DirectOrderFulfillment.Snapshot(
                                        status,
                                        method,
                                        3,
                                        amount,
                                        "XOF",
                                        confirmed,
                                        now.minusSeconds(60),
                                        now.minusSeconds(40),
                                        now.minusSeconds(20))));
    }

    @ParameterizedTest
    @CsvSource({
        "READY_FOR_DISPATCH,DELIVERY,DISPATCH,DISPATCHED",
        "DISPATCHED,DELIVERY,DELIVER,DELIVERED",
        "DELIVERED,DELIVERY,COMPLETE,COMPLETED",
        "READY_FOR_PICKUP,PICKUP,PICKUP,PICKED_UP",
        "PICKED_UP,PICKUP,COMPLETE,COMPLETED"
    })
    void onlyVerifiedPreviousStepCanAdvance(
            String previous, String method, String action, String next) {
        order(previous, method);
        assertThat(
                        service.transition(
                                        org,
                                        order,
                                        "handoff-intention-0001",
                                        new DirectOrderFulfillment.Command(action, 3L, true))
                                .toStatus())
                .isEqualTo(next);
        verify(payments).lockRequestState(org, requestId);
        verify(jdbc)
                .update(
                        startsWith("insert into direct_order_fulfillment_events"),
                        any(Object[].class));
    }

    @ParameterizedTest
    @CsvSource({
        "READY_FOR_PICKUP,PICKUP,DELIVER",
        "READY_FOR_DISPATCH,DELIVERY,COMPLETE",
        "DISPATCHED,DELIVERY,PICKUP",
        "PAYMENT_CONFIRMED,PICKUP,PICKUP"
    })
    void cannotSkipPhysicalSteps(String previous, String method, String action) {
        order(previous, method);
        assertThatThrownBy(
                        () ->
                                service.transition(
                                        org,
                                        order,
                                        "handoff-intention-0001",
                                        new DirectOrderFulfillment.Command(action, 3L, true)))
                .isInstanceOf(ApiException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void reversedOrMismatchedMoneyNeverReleasesGoods() {
        order("READY_FOR_PICKUP", "PICKUP");
        when(proof.reversal()).thenReturn(true);
        assertThatThrownBy(
                        () ->
                                service.transition(
                                        org,
                                        order,
                                        "handoff-intention-0001",
                                        new DirectOrderFulfillment.Command("PICKUP", 3L, true)))
                .isInstanceOf(ApiException.class);
        when(proof.reversal()).thenReturn(false);
        when(proof.amount()).thenReturn(new BigDecimal("19999"));
        assertThatThrownBy(
                        () ->
                                service.transition(
                                        org,
                                        order,
                                        "handoff-intention-0001",
                                        new DirectOrderFulfillment.Command("PICKUP", 3L, true)))
                .isInstanceOf(ApiException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
