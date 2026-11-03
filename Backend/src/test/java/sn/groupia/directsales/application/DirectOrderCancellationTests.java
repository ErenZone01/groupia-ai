package sn.groupia.directsales.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
import sn.groupia.payment.api.PaymentOperations;
import sn.groupia.shared.api.ApiException;

class DirectOrderCancellationTests {
    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
    private final UUID org = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private OrganizationAccess access;
    private PaymentOperations payments;
    private DirectOrderCancellation subject;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        access = mock(OrganizationAccess.class);
        payments = mock(PaymentOperations.class);
        subject =
                new DirectOrderCancellation(
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

    private void order(String status, Instant cancelledAt) {
        when(jdbc.query(
                        contains("select order_number,status,cancelled_at"),
                        any(RowMapper.class),
                        eq(org),
                        eq(orderId),
                        eq(storeId)))
                .thenReturn(
                        List.of(
                                new DirectOrderCancellation.Order(
                                        "DIR-000000000001", status, cancelledAt)));
    }

    private void safe(List<UUID> requests) {
        when(jdbc.queryForObject(
                        contains("select exists("), eq(Boolean.class), eq(org), eq(orderId)))
                .thenReturn(false);
        when(jdbc.query(
                        contains("select id from payment_requests"),
                        any(RowMapper.class),
                        eq(org),
                        eq(orderId)))
                .thenReturn(requests);
        when(jdbc.update(
                        contains("update direct_orders"),
                        eq(Timestamp.from(NOW)),
                        eq(actor),
                        eq(org),
                        eq(orderId)))
                .thenReturn(1);
    }

    @Test
    void pendingOrderWithoutObligationCanBeCancelled() {
        order("PENDING_PAYMENT_INTEGRATION", null);
        safe(List.of());

        var result = subject.cancel(org, orderId);

        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.cancelledAt()).isEqualTo(NOW);
        verifyNoInteractions(payments);
    }

    @Test
    void preparedObligationIsCancelledInTheSameCommand() {
        order("PENDING_PAYMENT_INTEGRATION", null);
        safe(List.of(requestId));

        assertThat(subject.cancel(org, orderId).status()).isEqualTo("CANCELLED");

        verify(payments).cancelDirectUnpaidRequest(org, requestId, orderId);
        verify(jdbc)
                .update(
                        contains("update direct_orders"),
                        eq(Timestamp.from(NOW)),
                        eq(actor),
                        eq(org),
                        eq(orderId));
    }

    @Test
    void aPreviouslyCancelledOrderIsIdempotent() {
        order("CANCELLED", NOW.minusSeconds(30));

        var receipt = subject.cancel(org, orderId);

        assertThat(receipt.status()).isEqualTo("CANCELLED");
        assertThat(receipt.cancelledAt()).isEqualTo(NOW.minusSeconds(30));
        verifyNoInteractions(payments);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void settlementEvidenceBlocksCancellation() {
        order("PENDING_PAYMENT_INTEGRATION", null);
        when(jdbc.queryForObject(
                        contains("select exists("), eq(Boolean.class), eq(org), eq(orderId)))
                .thenReturn(true);

        assertThatThrownBy(() -> subject.cancel(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("vérification financière");
        verifyNoInteractions(payments);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void paymentRiskFailureCannotCancelOrder() {
        order("PENDING_PAYMENT_INTEGRATION", null);
        safe(List.of(requestId));
        doThrow(new ApiException(409, "DIRECT_PAYMENT_CANCELLATION_BLOCKED", "Paiement en cours"))
                .when(payments)
                .cancelDirectUnpaidRequest(org, requestId, orderId);

        assertThatThrownBy(() -> subject.cancel(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Paiement en cours");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void deniedPermissionsOrForeignOrganizationNeverAccessOrderData() {
        doThrow(new ApiException(403, "ACCESS_DENIED", "Interdit"))
                .when(access)
                .requireActorPermission(org, OrganizationPermission.MANAGE_FULFILLMENT);

        assertThatThrownBy(() -> subject.cancel(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Interdit");
        verifyNoInteractions(payments);
        verifyNoInteractions(jdbc);

        reset(access);
        when(access.requireActorPermission(org, OrganizationPermission.MANAGE_FULFILLMENT))
                .thenReturn(actor);
        reset(jdbc);
        assertThatThrownBy(() -> subject.cancel(org, orderId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("introuvable");
    }
}
