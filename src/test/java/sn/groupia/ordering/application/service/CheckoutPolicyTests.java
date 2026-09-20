package sn.groupia.ordering.application.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import sn.groupia.grouping.api.CheckoutAdmission;
import sn.groupia.grouping.api.dto.*;
import sn.groupia.ordering.application.command.CheckoutCommand;
import sn.groupia.ordering.domain.model.*;
import sn.groupia.ordering.domain.repository.Orders;
import sn.groupia.shipping.api.*;
import sn.groupia.shipping.api.dto.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CheckoutPolicyTests {
    @Test void externalPolicyChangesApplyOnlyToNewOrders() {
        UUID org = UUID.randomUUID(), campaign = UUID.randomUUID(), publicId = UUID.randomUUID(), offerId = UUID.randomUUID();
        var grouping = mock(CheckoutAdmission.class);
        var shipping = mock(ShippingEstimator.class);
        var orders = mock(Orders.class);
        var context = new CheckoutCampaign(org,campaign,"SN");
        var terms = new ShippingTerms(null,TransportMode.AIR,ShippingPricingMethod.FIXED,
                ShippingCollectionMode.AFTER_FINAL_ASSESSMENT,BigDecimal.ZERO,"XOF","HALF_UP");
        var offer = new CheckoutOffer(offerId,1,new BigDecimal("1000"),0,0,UUID.randomUUID(),UUID.randomUUID(),
                "Produit",null,null,Map.of(),null,"UNIT",null,null);
        when(grouping.lockCampaign(publicId)).thenReturn(context);
        when(grouping.quote(eq(context),anyList())).thenReturn(new CheckoutQuote(org,campaign,"XOF","OPEN",true,terms,List.of(offer)));
        when(orders.nextOrderNumber()).thenReturn("GRP-000000000001","GRP-000000000002");
        when(shipping.estimate(eq(terms),anyList())).thenReturn(BigDecimal.ZERO);
        var clock = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"),ZoneOffset.UTC);
        var input = new CheckoutInput(); var secrets = new CheckoutSecrets(new ObjectMapper());
        var command = new CheckoutCommand(new CheckoutCommand.Contact("Awa","Diop","771234567",null),
                List.of(new CheckoutCommand.Line(offerId,1,new BigDecimal("1000"),0L)));
        var before = new CheckoutService(grouping,shipping,orders,input,secrets,new CheckoutPolicy(new BigDecimal("750"),45,90),clock,mock(sn.groupia.payment.api.PaymentOperations.class));
        var after = new CheckoutService(grouping,shipping,orders,input,secrets,new CheckoutPolicy(new BigDecimal("1200"),60,180),clock,mock(sn.groupia.payment.api.PaymentOperations.class));
        before.checkout(publicId,UUID.randomUUID().toString(),command);
        after.checkout(publicId,UUID.randomUUID().toString(),command);
        var captured = ArgumentCaptor.forClass(Order.class);
        verify(orders,times(2)).add(any(Customer.class),captured.capture());
        var old = captured.getAllValues().get(0); var recent = captured.getAllValues().get(1);
        assertThat(old.getPlatformFeeAmount()).isEqualByComparingTo("750");
        assertThat(old.getPlatformFeeRuleSnapshot()).containsEntry("amount","750");
        assertThat(old.getInitialAmountDue()).isEqualByComparingTo("1750");
        assertThat(old.getPaymentDeadline()).isEqualTo(clock.instant().plusSeconds(2700));
        assertThat(old.getTrackingTokenExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(90)));
        assertThat(recent.getInitialAmountDue()).isEqualByComparingTo("2200");
        assertThat(recent.getPlatformFeeRuleSnapshot()).containsEntry("amount","1200");
    }
    @ParameterizedTest @ValueSource(strings = {"-1","0.5","1000000000000000000"})
    void rejectsInvalidConfiguredFee(String fee) {
        assertThatThrownBy(() -> new CheckoutPolicy(new BigDecimal(fee),30,180)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void contactNormalizerUsesSuppliedCountryInsteadOfHardcodedRegion() {
        var input = new CheckoutInput();
        assertThat(input.contact(new CheckoutCommand.Contact("Awa","Diop","06 12 34 56 78",null),"FR").phoneE164())
                .isEqualTo("+33612345678");
    }
}
