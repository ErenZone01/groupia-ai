package sn.groupia.settlement.application.service;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import sn.groupia.ordering.api.MerchantOrderFacts;
import sn.groupia.ordering.api.event.OrderCommerciallyAccepted;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.PaymentRequestView;
import sn.groupia.payment.api.event.*;
import sn.groupia.settlement.domain.model.MerchantLedgerEntry;
import sn.groupia.settlement.domain.repository.MerchantFinanceStore;

@Component
public class MerchantLedgerListeners {
    private final MerchantFinanceStore store;
    private final PaymentOperations payments;
    private final RefundOperations refunds;
    private final MerchantOrderFacts orders;

    MerchantLedgerListeners(
            MerchantFinanceStore s, PaymentOperations p, RefundOperations r, MerchantOrderFacts o) {
        store = s;
        payments = p;
        refunds = r;
        orders = o;
    }

    @ApplicationModuleListener
    public void settled(PaymentRequestSettled e) {
        if (e.purpose() != PaymentPurpose.INITIAL_ORDER
                && e.purpose() != PaymentPurpose.SHIPPING_BALANCE) return;
        var r = payments.request(e.organizationId(), e.paymentRequestId());
        if (!java.util.Objects.equals(r.orderId(), e.orderId())
                || !e.paymentId().equals(r.settledPaymentId())
                || r.purpose() != e.purpose()
                || r.expectedAmount().compareTo(e.amount()) != 0
                || !r.currency().equals(e.currency()))
            throw new IllegalStateException("Merchant settlement event mismatch");
        collect(r);
    }

    @ApplicationModuleListener
    public void accepted(OrderCommerciallyAccepted e) {
        var o = orders.read(e.organizationId(), e.orderId());
        if (!o.campaignId().equals(e.campaignId()))
            throw new IllegalStateException("Merchant acceptance mismatch");
        if (!o.accepted()) return;
        var request = payments.initialRequest(e.organizationId(), e.orderId());
        if (request.acceptanceStatus() == AcceptanceStatus.CLOSED) collect(request);
    }

    private void collect(PaymentRequestView r) {
        if (r.target().type() != PaymentTargetType.ORDER
                || (r.purpose() != PaymentPurpose.INITIAL_ORDER
                        && r.purpose() != PaymentPurpose.SHIPPING_BALANCE))
            throw new IllegalStateException("Expected order collection");
        if (r.acceptanceStatus() != AcceptanceStatus.CLOSED
                || r.settledPaymentId() == null
                || r.closedAt() == null
                || !"XOF".equals(r.currency()))
            throw new IllegalStateException("Merchant collection not settled");
        var o = orders.read(r.organizationId(), r.orderId());
        // INITIAL_ORDER's consumer may run before ordering's confirmation. Its durable acceptance
        // event finishes attribution.
        if (!o.accepted()) {
            if (r.purpose() == PaymentPurpose.INITIAL_ORDER) return;
            throw new IllegalStateException("Merchant shipping order not accepted");
        }
        if (r.purpose() == PaymentPurpose.SHIPPING_BALANCE
                && (r.feeAmount().signum() != 0 || r.goodsAmount().signum() != 0))
            throw new IllegalStateException("Invalid shipping collection breakdown");
        store.lockOrganization(r.organizationId());
        store.append(
                new MerchantLedgerEntry(
                        UUID.randomUUID(),
                        r.organizationId(),
                        o.campaignId(),
                        o.orderId(),
                        r.purpose() == PaymentPurpose.INITIAL_ORDER
                                ? "ORDER_COLLECTION"
                                : "SHIPPING_BALANCE_COLLECTION",
                        "CREDIT",
                        r.goodsAmount().add(r.shippingAmount()),
                        r.currency(),
                        r.feeAmount(),
                        "COLLECTION:" + r.id(),
                        "PAYMENT_REQUEST",
                        r.id(),
                        r.closedAt()));
    }

    @ApplicationModuleListener
    public void refunded(ShippingCreditRefundSettled e) {
        var r =
                refunds.history(e.organizationId(), e.orderId()).stream()
                        .filter(x -> x.id().equals(e.refundId()))
                        .findFirst()
                        .orElseThrow();
        if (!r.status().equals("SUCCEEDED")
                || r.amount().compareTo(e.amount()) != 0
                || !r.currency().equals(e.currency())
                || r.completedAt() == null)
            throw new IllegalStateException("Merchant refund event mismatch");
        if (r.method().equals("MANUAL_EXTERNAL"))
            return; // Merchant's own money: never charge her twice.
        if (!r.method().equals("BICTORYS_PAYOUT"))
            throw new IllegalStateException("Unknown refund funding");
        var o = orders.read(e.organizationId(), e.orderId());
        store.lockOrganization(e.organizationId());
        store.append(
                new MerchantLedgerEntry(
                        UUID.randomUUID(),
                        e.organizationId(),
                        o.campaignId(),
                        e.orderId(),
                        "PLATFORM_FUNDED_CUSTOMER_REFUND",
                        "DEBIT",
                        r.amount(),
                        r.currency(),
                        BigDecimal.ZERO,
                        "CUSTOMER_REFUND:" + r.id(),
                        "CUSTOMER_REFUND",
                        r.id(),
                        r.completedAt()));
    }
}
