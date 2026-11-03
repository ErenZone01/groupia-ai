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

class DirectMerchantPaymentReadinessTests {
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    private final UUID organization = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private OrganizationAccess access;
    private PaymentOperations payments;
    private DirectMerchantPaymentReadiness subject;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        access = mock(OrganizationAccess.class);
        payments = mock(PaymentOperations.class);
        subject =
                new DirectMerchantPaymentReadiness(
                        jdbc, access, payments, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void snapshot(UUID request, UUID settled, BigDecimal amount) {
        when(jdbc.query(
                        contains("from direct_orders d"),
                        any(RowMapper.class),
                        eq(organization),
                        eq(order)))
                .thenReturn(
                        List.of(
                                new DirectMerchantPaymentReadiness.Snapshot(
                                        "DIR-000000000001", "XOF", 10300L, request, settled, amount)));
    }

    private PaymentRequestView obligation(UUID settled) {
        PaymentRequestView view = mock(PaymentRequestView.class);
        when(view.id()).thenReturn(requestId);
        when(view.organizationId()).thenReturn(organization);
        when(view.target()).thenReturn(PaymentTarget.directOrder(order));
        when(view.purpose()).thenReturn(PaymentPurpose.DIRECT_ORDER_INITIAL);
        when(view.expectedAmount()).thenReturn(BigDecimal.valueOf(10300));
        when(view.currency()).thenReturn("XOF");
        when(view.acceptanceStatus())
                .thenReturn(settled == null ? AcceptanceStatus.OPEN : AcceptanceStatus.CLOSED);
        when(view.settledPaymentId()).thenReturn(settled);
        when(view.expiresAt()).thenReturn(NOW.plusSeconds(3600));
        return view;
    }

    private void state(PaymentOperationalStatus status, UUID settled) {
        when(payments.requestState(organization, requestId))
                .thenReturn(new PaymentRequestState(obligation(settled), status, false, false));
    }

    private PaymentSettlementEvidence proof(boolean reversed) {
        return new PaymentSettlementEvidence(
                organization,
                requestId,
                paymentId,
                PaymentTarget.directOrder(order),
                PaymentPurpose.DIRECT_ORDER_INITIAL,
                BigDecimal.valueOf(10300),
                "XOF",
                AcceptanceStatus.CLOSED,
                PaymentStatus.SUCCEEDED,
                "BICTORYS",
                "account",
                "gateway-transaction",
                "gateway-ref",
                NOW.minusSeconds(20),
                NOW.plusSeconds(3600),
                reversed);
    }

    @Test
    void anUnpreparedOrderCannotBeFulfilled() {
        snapshot(null, null, null);

        var result = subject.get(organization, order);

        assertThat(result.financialStatus()).isEqualTo("NOT_PREPARED");
        assertThat(result.fulfillmentAllowed()).isFalse();
        verify(access).requirePermission(organization, OrganizationPermission.VIEW_ORDERS);
        verifyNoInteractions(payments);
    }

    @Test
    void anOpenObligationIsNotPaymentEvidence() {
        snapshot(requestId, null, null);
        state(PaymentOperationalStatus.PENDING, null);

        var result = subject.get(organization, order);

        assertThat(result.financialStatus()).isEqualTo("PREPARED");
        assertThat(result.acceptedTotalXof()).isEqualTo(10300L);
        assertThat(result.fulfillmentAllowed()).isFalse();
        verify(payments, never()).settlementEvidence(any(), any());
    }

    @Test
    void settledButNotYetRecordedCannotReleaseTheOrder() {
        snapshot(requestId, null, null);
        state(PaymentOperationalStatus.SETTLED, paymentId);

        assertThat(subject.get(organization, order).financialStatus())
                .isEqualTo("SETTLEMENT_PROCESSING");
        verify(payments, never()).settlementEvidence(any(), any());
    }

    @Test
    void recordedCanonicalPaymentIsVerifiedButStillCannotReleaseFulfillment() {
        snapshot(requestId, paymentId, BigDecimal.valueOf(10300));
        state(PaymentOperationalStatus.SETTLED, paymentId);
        when(payments.settlementEvidence(organization, requestId)).thenReturn(proof(false));

        var result = subject.get(organization, order);

        assertThat(result.financialStatus()).isEqualTo("SETTLEMENT_VERIFIED");
        assertThat(result.fulfillmentAllowed()).isFalse();
    }

    @Test
    void reversalOrContradictorySettlementRequiresReview() {
        snapshot(requestId, paymentId, BigDecimal.valueOf(10300));
        state(PaymentOperationalStatus.SETTLED, paymentId);
        when(payments.settlementEvidence(organization, requestId)).thenReturn(proof(true));

        assertThat(subject.get(organization, order).financialStatus())
                .isEqualTo("REVIEW_REQUIRED");
        assertThat(subject.get(organization, order).fulfillmentAllowed()).isFalse();

        when(payments.settlementEvidence(organization, requestId))
                .thenReturn(
                        new PaymentSettlementEvidence(
                                organization,
                                requestId,
                                UUID.randomUUID(),
                                PaymentTarget.directOrder(order),
                                PaymentPurpose.DIRECT_ORDER_INITIAL,
                                BigDecimal.valueOf(10300),
                                "XOF",
                                AcceptanceStatus.CLOSED,
                                PaymentStatus.SUCCEEDED,
                                "BICTORYS",
                                "account",
                                "different-transaction",
                                "gateway-ref",
                                NOW.minusSeconds(20),
                                NOW.plusSeconds(3600),
                                false));
        assertThat(subject.get(organization, order).financialStatus())
                .isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void operationalRiskTakesPrecedenceOverVerifiedReceipts() {
        snapshot(requestId, paymentId, BigDecimal.valueOf(10300));
        state(PaymentOperationalStatus.REVIEW_REQUIRED, paymentId);

        assertThat(subject.get(organization, order).financialStatus())
                .isEqualTo("REVIEW_REQUIRED");
        verify(payments, never()).settlementEvidence(any(), any());
    }

    @Test
    void anUnknownOrderAndUnauthorizedMerchantCannotEnumerateOthers() {
        assertThatThrownBy(() -> subject.get(organization, order))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("introuvable");
        verify(access).requirePermission(organization, OrganizationPermission.VIEW_ORDERS);
        verifyNoInteractions(payments);

        reset(jdbc);
        doThrow(new ApiException(403, "ACCESS_DENIED", "Interdit"))
                .when(access)
                .requirePermission(organization, OrganizationPermission.VIEW_ORDERS);
        assertThatThrownBy(() -> subject.get(organization, order))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Interdit");
        verifyNoInteractions(jdbc);
    }
}
