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
