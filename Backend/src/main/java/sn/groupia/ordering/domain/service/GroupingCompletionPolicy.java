package sn.groupia.ordering.domain.service;

import java.util.*;
import sn.groupia.ordering.api.dto.GroupingCompletionView.Blocker;
import sn.groupia.ordering.api.dto.ShippingSettlementView;
import sn.groupia.ordering.domain.model.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.OrderPaymentCompletionFacts;

public final class GroupingCompletionPolicy {
    private GroupingCompletionPolicy() {}

    public static List<Blocker> evaluate(
            Order o, OrderPaymentCompletionFacts facts, ShippingSettlementView shipping) {
        return evaluate(o, facts, shipping, false);
    }

    public static List<Blocker> evaluate(
            Order o,
            OrderPaymentCompletionFacts facts,
            ShippingSettlementView shipping,
            boolean commercialRefundRecorded) {
        var codes = new LinkedHashSet<String>();
        boolean refunded = o.getStatus() == OrderStatus.REFUNDED && commercialRefundRecorded;
        if (o.getStatus() == OrderStatus.REFUND_PENDING) codes.add("COMMERCIAL_REFUND_DUE");
        if (o.getStatus() == OrderStatus.REFUNDED && !refunded)
            codes.add("COMMERCIAL_REFUND_UNVERIFIED");
        boolean executed = o.getStatus() == OrderStatus.COMPLETED;
        boolean terminal =
                o.getStatus() == OrderStatus.EXPIRED
                        || o.getStatus() == OrderStatus.CANCELLED
                        || refunded;
        if (!executed && !terminal) codes.add("ORDER_NOT_COMPLETED");
        if (o.getFulfillmentStatus() == FulfillmentStatus.READY_FOR_PICKUP)
            codes.add("ORDER_PICKUP_REQUIRED");
        if (executed
                && (o.getFulfillmentStatus() != FulfillmentStatus.PICKED_UP
                        || o.getPickedUpAt() == null
                        || o.getCompletedAt() == null))
            codes.add("GROUPING_COMPLETION_INCONSISTENT_STATE");
        if (terminal
                && o.getFulfillmentStatus() != FulfillmentStatus.NOT_READY
                && o.getFulfillmentStatus() != FulfillmentStatus.CANCELLED)
            codes.add("GROUPING_COMPLETION_INCONSISTENT_STATE");
        if (!terminal
                && shipping != null
                && shipping.status() != ShippingSettlementView.Status.SETTLED)
            codes.add("SHIPPING_" + shipping.status());
        var initials =
                facts.obligations().stream()
                        .filter(v -> v.request().purpose() == PaymentPurpose.INITIAL_ORDER)
                        .toList();
        if (initials.size() != 1) codes.add("INITIAL_PAYMENT_INCONSISTENT");
        for (var fact : facts.obligations()) {
            var p = fact.request();
            if (p.target().type() != PaymentTargetType.ORDER
                    || (p.purpose() != PaymentPurpose.INITIAL_ORDER
                            && p.purpose() != PaymentPurpose.SHIPPING_BALANCE)) continue;
            codes.addAll(fact.risks());
            if (fact.pendingReceipt()) codes.add("PAYMENT_RECEIPT_PENDING");
            if (p.acceptanceStatus() == AcceptanceStatus.OPEN) codes.add("PAYMENT_OBLIGATION_OPEN");
            if (!p.organizationId().equals(o.getOrganizationId())
                    || !Objects.equals(p.orderId(), o.getId())
                    || !p.currency().equals(o.getCurrency()))
                codes.add("PAYMENT_OBLIGATION_INCONSISTENT");
            if (p.purpose() == PaymentPurpose.INITIAL_ORDER
                    && (p.expectedAmount().compareTo(o.getInitialAmountDue()) != 0
                            || p.goodsAmount().compareTo(o.getGoodsSubtotal()) != 0
                            || p.feeAmount().compareTo(o.getPlatformFeeAmount()) != 0
                            || p.shippingAmount().compareTo(o.getInitialShippingChargeAmount())
                                    != 0)) codes.add("INITIAL_PAYMENT_INCONSISTENT");
            if (executed
                    && p.purpose() == PaymentPurpose.INITIAL_ORDER
                    && (p.acceptanceStatus() != AcceptanceStatus.CLOSED
                            || p.settledPaymentId() == null))
                codes.add("INITIAL_PAYMENT_NOT_SETTLED");
            if (terminal && !refunded && p.settledPaymentId() != null)
                codes.add("TERMINAL_ORDER_PAID");
        }
        for (var refund : facts.refunds()) {
            if ((terminal && !refunded)
                    || !refund.status().equals("SUCCEEDED")
                    || refund.reconciliationRequired()) codes.add("REFUND_UNRESOLVED");
        }
        return codes.stream().map(c -> new Blocker(c, o.getId(), o.getOrderNumber())).toList();
    }
}
