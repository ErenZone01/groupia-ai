package sn.groupia;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sn.groupia.ordering.domain.model.*;
import sn.groupia.ordering.domain.service.GroupingCompletionPolicy;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;

class SubscriptionCompletionIsolationTests {
    @ParameterizedTest
    @ValueSource(strings = {"pending", "settled", "review"})
    void subscriptionCannotAddOrRemoveAnyCompletionBlocker(String state) {
        var order = mock(Order.class);
        when(order.getId()).thenReturn(UUID.randomUUID());
        when(order.getOrderNumber()).thenReturn("GRP-TEST");
        when(order.getStatus()).thenReturn(OrderStatus.CANCELLED);
        when(order.getFulfillmentStatus()).thenReturn(FulfillmentStatus.CANCELLED);
        var request =
                new PaymentRequestView(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        PaymentPurpose.SUBSCRIPTION_MONTHLY,
                        "TEST",
                        BigDecimal.TEN,
                        "XOF",
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        state.equals("settled") ? AcceptanceStatus.CLOSED : AcceptanceStatus.OPEN,
                        Instant.now().plusSeconds(60),
                        state.equals("settled") ? UUID.randomUUID() : null,
                        Instant.now(),
                        null,
                        null,
                        0,
                        PaymentTarget.subscription(UUID.randomUUID()));
        var baseline =
                GroupingCompletionPolicy.evaluate(
                        order, new OrderPaymentCompletionFacts(List.of(), List.of()), null);
        var subscription =
                new OrderPaymentCompletionFacts.Obligation(
                        request,
                        state.equals("review") ? Set.of("PAYMENT_REVIEW_OPEN") : Set.of(),
                        true);
        assertThat(
                        GroupingCompletionPolicy.evaluate(
                                order,
                                new OrderPaymentCompletionFacts(List.of(subscription), List.of()),
                                null))
                .isEqualTo(baseline);
    }
}
