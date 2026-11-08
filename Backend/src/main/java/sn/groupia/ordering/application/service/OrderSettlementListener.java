package sn.groupia.ordering.application.service;

import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import sn.groupia.ordering.domain.repository.Orders;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.event.PaymentRequestSettled;

@Component
public class OrderSettlementListener {
    private final ShippingFinance finance;
    private final Orders orders;
    private final PaymentOperations payments;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final java.time.Clock clock;

    OrderSettlementListener(
            Orders orders,
            PaymentOperations payments,
            org.springframework.context.ApplicationEventPublisher events,
            java.time.Clock clock,
            ShippingFinance finance) {
        this.finance = finance;
        this.orders = orders;
        this.payments = payments;
        this.events = events;
        this.clock = clock;
    }

    @ApplicationModuleListener
    public void on(PaymentRequestSettled event) {

        if (event.purpose() != PaymentPurpose.INITIAL_ORDER
                && event.purpose() != PaymentPurpose.SHIPPING_BALANCE) return;
        var scoped = payments.request(event.organizationId(), event.paymentRequestId());
        if (scoped.target().type() != PaymentTargetType.ORDER
                || !scoped.target().id().equals(event.orderId()))
            throw new IllegalStateException("Expected order settlement");
        var order =
                orders.lock(event.orderId())
                        .orElseThrow(() -> new IllegalStateException("Settlement order missing"));
        var request = payments.request(event.organizationId(), event.paymentRequestId());
        if (request.purpose() != event.purpose()
                || !java.util.Objects.equals(request.orderId(), event.orderId())
                || !order.getOrganizationId().equals(event.organizationId())
                || !request.id().equals(event.paymentRequestId())
                || !event.paymentId().equals(request.settledPaymentId())
                || request.acceptanceStatus() != AcceptanceStatus.CLOSED
                || !request.currency().equals(event.currency())
                || request.expectedAmount().compareTo(event.amount()) != 0) {
            throw new IllegalStateException("Settlement contract inconsistent");
        }
        // A cancellation preserves the original verified collection; late delivery of its durable
        // event must neither reconfirm the order nor create a poison publication.
        if (order.getStatus() == sn.groupia.ordering.domain.model.OrderStatus.REFUND_PENDING
                || order.getStatus() == sn.groupia.ordering.domain.model.OrderStatus.REFUNDED)
            return;
        if (event.purpose() == PaymentPurpose.SHIPPING_BALANCE) {
            if (order.getStatus() != sn.groupia.ordering.domain.model.OrderStatus.CONFIRMED
                    && order.getStatus() != sn.groupia.ordering.domain.model.OrderStatus.COMPLETED)
                throw new IllegalStateException("Shipping settlement on ineligible order");
            finance.read(order); // Validate current sources; no commercial/logistical transition or
            // duplicated projection.
            return;
        }
        if (event.purpose() == PaymentPurpose.INITIAL_ORDER
                && order.confirmInitialPayment(
                        event.amount(), event.currency(), event.occurredAt())) {
            events.publishEvent(
                    new sn.groupia.ordering.api.event.OrderCommerciallyAccepted(
                            java.util.UUID.randomUUID(),
                            clock.instant(),
                            order.getOrganizationId(),
                            order.getCampaignId(),
                            order.getId()));
        } else {
            if (order.getStatus() != sn.groupia.ordering.domain.model.OrderStatus.CONFIRMED
                    && order.getStatus()
                            != sn.groupia.ordering.domain.model.OrderStatus.COMPLETED) {
                LoggerFactory.getLogger(getClass())
                        .error("Settlement cannot confirm ineligible order {}", event.orderId());
            }
        }
    }
}
