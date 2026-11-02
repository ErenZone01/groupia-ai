package sn.groupia.payment.domain.model;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.shared.api.ApiException;

class PaymentTargetTests {
    final UUID org = UUID.randomUUID(), target = UUID.randomUUID();
    final Instant now = Instant.parse("2026-10-16T00:00:00Z");

    @ParameterizedTest
    @EnumSource(
            value = PaymentPurpose.class,
            names = {"INITIAL_ORDER", "SHIPPING_BALANCE"})
    void orderPurposesKeepTheirOrderTarget(PaymentPurpose purpose) {
        var r =
                PaymentRequest.create(
                        CreatePaymentRequest.forOrder(
                                org,
                                target,
                                purpose,
                                "TEST",
                                BigDecimal.TEN,
                                "XOF",
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                BigDecimal.TEN,
                                now.plusSeconds(60)),
                        now);
        assertThat(r.getTarget()).isEqualTo(PaymentTarget.order(target));
        assertThat(r.getOrderId()).isEqualTo(target);
    }

    @Test
    void subscriptionUsesOpaqueTargetAndZeroOrderBreakdown() {
        var r =
                PaymentRequest.create(
                        CreatePaymentRequest.forSubscription(
                                org, target, BigDecimal.TEN, now.plusSeconds(60)),
                        now);
        assertThat(r.getOrderId()).isNull();
        assertThat(r.getTarget()).isEqualTo(PaymentTarget.subscription(target));
        assertThat(r.getGoodsAmount().add(r.getFeeAmount()).add(r.getShippingAmount())).isZero();
    }

    @Test
    void directOrderPurposeKeepsDistinctTargetAndExactBreakdown() {
        var request =
                PaymentRequest.create(
                        CreatePaymentRequest.forDirectOrder(
                                org,
                                target,
                                BigDecimal.valueOf(8500),
                                BigDecimal.valueOf(300),
                                BigDecimal.valueOf(1500),
                                now.plusSeconds(300)),
                        now);
        assertThat(request.getTarget()).isEqualTo(PaymentTarget.directOrder(target));
        assertThat(request.getOrderId()).isNull();
        assertThat(request.getPurpose()).isEqualTo(PaymentPurpose.DIRECT_ORDER_INITIAL);
        assertThat(request.getExpectedAmount()).isEqualByComparingTo("10300");
        assertThat(request.getGoodsAmount()).isEqualByComparingTo("8500");
        assertThat(request.getFeeAmount()).isEqualByComparingTo("300");
        assertThat(request.getShippingAmount()).isEqualByComparingTo("1500");
    }

    @Test
    void directOrderRequiresDeadlinePositiveTotalAndHonestBreakdown() {
        assertThatThrownBy(
                        () ->
                                PaymentRequest.create(
                                        CreatePaymentRequest.forDirectOrder(
                                                org, target, BigDecimal.TEN, BigDecimal.ZERO,
                                                BigDecimal.ZERO, null),
                                        now))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(
                        () ->
                                PaymentRequest.create(
                                        CreatePaymentRequest.forDirectOrder(
                                                org, target, BigDecimal.ZERO, BigDecimal.ZERO,
                                                BigDecimal.ZERO, now.plusSeconds(300)),
                                        now))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(
                        () ->
                                PaymentRequest.create(
                                        new CreatePaymentRequest(
                                                org,
                                                PaymentTarget.directOrder(target),
                                                PaymentPurpose.DIRECT_ORDER_INITIAL,
                                                "DIRECT_ORDER_INITIAL:" + target,
                                                BigDecimal.valueOf(1200),
                                                "XOF",
                                                BigDecimal.valueOf(1000),
                                                BigDecimal.valueOf(100),
                                                BigDecimal.ZERO,
                                                now.plusSeconds(300)),
                                        now))
                .isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @EnumSource(PaymentPurpose.class)
    void contradictoryTargetIsRejected(PaymentPurpose purpose) {
        var t =
                purpose == PaymentPurpose.SUBSCRIPTION_MONTHLY
                        ? PaymentTarget.order(target)
                        : PaymentTarget.subscription(target);
        assertThatThrownBy(
                        () ->
                                PaymentRequest.create(
                                        new CreatePaymentRequest(
                                                org,
                                                t,
                                                purpose,
                                                "TEST",
                                                BigDecimal.TEN,
                                                "XOF",
                                                BigDecimal.ZERO,
                                                BigDecimal.ZERO,
                                                BigDecimal.ZERO,
                                                now.plusSeconds(60)),
                                        now))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void subscriptionCannotOmitExpiryOrUseAnOrderBreakdown() {
        assertThatThrownBy(
                        () ->
                                PaymentRequest.create(
                                        CreatePaymentRequest.forSubscription(
                                                org, target, BigDecimal.TEN, null),
                                        now))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(
                        () ->
                                PaymentRequest.create(
                                        new CreatePaymentRequest(
                                                org,
                                                PaymentTarget.subscription(target),
                                                PaymentPurpose.SUBSCRIPTION_MONTHLY,
                                                "TEST",
                                                BigDecimal.TEN,
                                                "XOF",
                                                BigDecimal.TEN,
                                                BigDecimal.ZERO,
                                                BigDecimal.ZERO,
                                                now.plusSeconds(60)),
                                        now))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(
                        () ->
                                PaymentRequest.create(
                                        CreatePaymentRequest.forSubscription(
                                                org, target, BigDecimal.ZERO, now.plusSeconds(60)),
                                        now))
                .isInstanceOf(ApiException.class);
    }
}
