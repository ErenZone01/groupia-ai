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
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import sn.groupia.organization.api.OrganizationAvailability;
import sn.groupia.payment.api.PaymentOperations;
import sn.groupia.payment.api.PaymentPurpose;
import sn.groupia.payment.api.PaymentTarget;
import sn.groupia.payment.api.dto.CreatePaymentRequest;
import sn.groupia.payment.api.dto.PaymentRequestView;
import sn.groupia.shared.api.ApiException;
import tools.jackson.databind.ObjectMapper;

class DirectPaymentObligationsTests {
    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private final UUID org = UUID.randomUUID();
    private final UUID store = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private PaymentOperations payments;
    private DirectDeliveryPolicyService delivery;
    private DirectRegionGeometry geometry;
    private OrganizationAvailability organizations;
    private DirectPaymentObligations subject;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        payments = mock(PaymentOperations.class);
        delivery = mock(DirectDeliveryPolicyService.class);
        geometry = mock(DirectRegionGeometry.class);
        organizations = mock(OrganizationAvailability.class);
        subject =
                new DirectPaymentObligations(
                        jdbc,
                        payments,
                        delivery,
                        geometry,
                        organizations,
                        new ObjectMapper(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        when(organizations.isActive(org)).thenReturn(true);
        when(jdbc.query(
                        contains("from direct_storefronts s"),
                        any(RowMapper.class),
                        eq(org),
                        eq(order)))
                .thenReturn(List.of(store));
        when(jdbc.queryForObject(
                        contains("select count(*) from direct_order_items"),
                        eq(Long.class),
                        eq(org),
                        eq(order)))
                .thenReturn(1L);
        when(jdbc.queryForObject(
                        contains("coalesce(sum(line_total_xof)"),
                        eq(BigDecimal.class),
                        eq(org),
                        eq(order)))
                .thenReturn(BigDecimal.valueOf(8500));
    }

    private void order(
            String method,
            Long shipping,
            Long accepted,
            String address,
            boolean quoted,
            Instant placedAt)
            throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(order);
        when(rs.getString("status")).thenReturn("PENDING_PAYMENT_INTEGRATION");
        when(rs.getString("currency")).thenReturn("XOF");
        when(rs.getString("fulfillment_method")).thenReturn(method);
        when(rs.getLong("goods_subtotal_xof")).thenReturn(8500L);
        when(rs.getLong("platform_fee_xof")).thenReturn(300L);
        when(rs.getLong("amount_before_shipping_xof")).thenReturn(8800L);
        when(rs.getBoolean("shipping_quoted")).thenReturn(quoted);
        when(rs.getObject("shipping_fee_xof", Long.class)).thenReturn(shipping);
        when(rs.getObject("accepted_total_xof", Long.class)).thenReturn(accepted);
        when(rs.getObject("shipping_rate_version", Long.class))
                .thenReturn("DELIVERY".equals(method) ? 1L : null);
        when(rs.getString("address_json")).thenReturn(address);
        when(rs.getTimestamp("placed_at")).thenReturn(Timestamp.from(placedAt));

        when(jdbc.query(
                        contains("from direct_orders where organization_id"),
                        any(RowMapper.class),
                        eq(org),
                        eq(order),
                        eq(store)))
                .thenAnswer(
                        invocation -> {
                            RowMapper<?> mapper = invocation.getArgument(1);
                            return List.of(mapper.mapRow(rs, 0));
                        });
    }

    @Test
    void pickupUsesOnlyPersistedAmountsAndDeterministicDeadline() throws Exception {
        order("PICKUP", 0L, 8800L, null, true, NOW.minusSeconds(120));
        PaymentRequestView receipt = mock(PaymentRequestView.class);
        when(payments.createRequest(any(CreatePaymentRequest.class))).thenReturn(receipt);

        assertThat(subject.prepare(org, order)).isSameAs(receipt);
        ArgumentCaptor<CreatePaymentRequest> command =
                ArgumentCaptor.forClass(CreatePaymentRequest.class);
        verify(payments).createRequest(command.capture());
        assertThat(command.getValue().target()).isEqualTo(PaymentTarget.directOrder(order));
        assertThat(command.getValue().purpose()).isEqualTo(PaymentPurpose.DIRECT_ORDER_INITIAL);
        assertThat(command.getValue().organizationId()).isEqualTo(org);
        assertThat(command.getValue().expectedAmount()).isEqualByComparingTo("8800");
        assertThat(command.getValue().goodsAmount()).isEqualByComparingTo("8500");
        assertThat(command.getValue().feeAmount()).isEqualByComparingTo("300");
        assertThat(command.getValue().shippingAmount()).isZero();
        assertThat(command.getValue().expiresAt())
                .isEqualTo(NOW.minusSeconds(120).plusSeconds(86400));
        verifyNoInteractions(delivery);
    }

    @Test
    void mapDeliveryNeedsCurrentPolygonAndMerchantCoverage() throws Exception {
        order(
                "DELIVERY",
                1500L,
                10300L,
                """
                {"country":"SN","region":"Dakar","latitude":14.69,"longitude":-17.44,
                 "locationMethod":"MAP","coverageStatus":"REGION_MATCHED"}
                """,
                true,
                NOW.minusSeconds(10));
        when(geometry.locate(14.69, -17.44))
                .thenReturn(new DirectRegionGeometry.Match("FOUND", "Dakar", "local-ADM1"));

        subject.prepare(org, order);
        verify(delivery).requireDeliveryAllowed(org, store, "Dakar");
        ArgumentCaptor<CreatePaymentRequest> request =
                ArgumentCaptor.forClass(CreatePaymentRequest.class);
        verify(payments).createRequest(request.capture());
        assertThat(request.getValue().shippingAmount()).isEqualByComparingTo("1500");
        assertThat(request.getValue().expectedAmount()).isEqualByComparingTo("10300");
    }

    @Test
    void manualAndUnquotedDraftsCannotCreatePaymentRequests() throws Exception {
        order(
                "DELIVERY",
                1500L,
                10300L,
                """
                {"country":"SN","region":"Dakar",
                 "locationMethod":"MANUAL","coverageStatus":"UNVERIFIED_MANUAL"}
                """,
                true,
                NOW.minusSeconds(10));
        assertThatThrownBy(() -> subject.prepare(org, order)).isInstanceOf(ApiException.class);
        verifyNoInteractions(payments);

        order("PICKUP", null, null, null, true, NOW.minusSeconds(10));
        assertThatThrownBy(() -> subject.prepare(org, order)).isInstanceOf(ApiException.class);
        verifyNoInteractions(payments);
    }

    @Test
    void oldOrCorruptedAmountsAndItemsAreRejected() throws Exception {
        order("PICKUP", 0L, 8800L, null, true, NOW.minusSeconds(90000));
        assertThatThrownBy(() -> subject.prepare(org, order)).isInstanceOf(ApiException.class);
        verifyNoInteractions(payments);

        order("PICKUP", 0L, 8800L, null, true, NOW.minusSeconds(10));
        when(jdbc.queryForObject(
                        contains("coalesce(sum(line_total_xof)"),
                        eq(BigDecimal.class),
                        eq(org),
                        eq(order)))
                .thenReturn(BigDecimal.valueOf(5000));
        assertThatThrownBy(() -> subject.prepare(org, order)).isInstanceOf(ApiException.class);
        verifyNoInteractions(payments);
    }

    @Test
    void missingOrganizationScopedOrderIsNotDisclosed() {
        when(jdbc.query(
                        contains("from direct_storefronts s"),
                        any(RowMapper.class),
                        eq(org),
                        eq(order)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> subject.prepare(org, order))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("introuvable");
        verifyNoInteractions(payments);
    }

    @Test
    void alteredMapRegionNeverCreatesObligation() throws Exception {
        order(
                "DELIVERY",
                1500L,
                10300L,
                """
                {"country":"SN","region":"Dakar","latitude":14.69,"longitude":-17.44,
                 "locationMethod":"MAP","coverageStatus":"REGION_MATCHED"}
                """,
                true,
                NOW.minusSeconds(10));
        when(geometry.locate(14.69, -17.44))
                .thenReturn(new DirectRegionGeometry.Match("FOUND", "Thiès", "local-ADM1"));
        assertThatThrownBy(() -> subject.prepare(org, order)).isInstanceOf(ApiException.class);
        verifyNoInteractions(payments);
        verifyNoInteractions(delivery);
    }
}
