package sn.groupia.directsales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import sn.groupia.organization.api.OrganizationAccess;
import sn.groupia.subscription.api.CommercialRevenuePolicy;
import tools.jackson.databind.ObjectMapper;

class DirectPickupQuoteTests {
    private static final UUID SHOP = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static DirectShippingFeesService shipping(JdbcTemplate jdbc, DirectCartQuoteService carts,
            boolean pickupRequired) {
        return new DirectShippingFeesService(
                jdbc,
                mock(ObjectMapper.class),
                Clock.systemUTC(),
                mock(OrganizationAccess.class),
                mock(DirectStorefrontService.class),
                mock(DirectDeliveryPolicyService.class),
                mock(DirectRegionGeometry.class),
                carts,
                new CommercialRevenuePolicy(new BigDecimal("300"), false),
                pickupRequired);
    }

    private static DirectShippingFeesService.PreviewRequest pickup() {
        return new DirectShippingFeesService.PreviewRequest(
                "PICKUP",
                List.of(new DirectCartQuoteService.RequestedLine(UUID.randomUUID(), 1)),
                null,
                null);
    }

    private static DirectCartQuoteService mockQuote() {
        var carts = mock(DirectCartQuoteService.class);
        when(carts.quote(eq(SHOP), anyList())).thenReturn(
                new DirectCartQuoteService.Quote(
                        "XOF", "Boutique Awa", 8500L, List.of(), false, true, null, null));
        return carts;
    }

    @Test
    void pickupWithoutPublishedMerchantAddressCannotProducePaymentQuote() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(SHOP)))
                .thenReturn(false);
        var result = shipping(jdbc, mockQuote(), true).preview(SHOP, pickup());

        assertThat(result.status()).isEqualTo("PICKUP_UNAVAILABLE");
        assertThat(result.shippingQuoted()).isFalse();
        assertThat(result.totalXof()).isNull();
        assertThat(result.platformFeeXof()).isZero();
    }

    @Test
    void configuredPickupCanBeQuotedWithZeroPlatformFee() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(SHOP)))
                .thenReturn(true);
        var result = shipping(jdbc, mockQuote(), true).preview(SHOP, pickup());

        assertThat(result.status()).isEqualTo("QUOTED");
        assertThat(result.totalXof()).isEqualTo(8500L);
        assertThat(result.shippingFeeXof()).isZero();
        assertThat(result.platformFeeXof()).isZero();
    }

    @Test
    void legacySandboxCanStillQuoteItsHistoricPickupFixtures() {
        var jdbc = mock(JdbcTemplate.class);
        var result = shipping(jdbc, mockQuote(), false).preview(SHOP, pickup());

        assertThat(result.status()).isEqualTo("QUOTED");
        verifyNoInteractions(jdbc);
    }
}
