package sn.groupia.ordering.application.service;
import org.springframework.stereotype.Component;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.slf4j.LoggerFactory;
import sn.groupia.ordering.domain.repository.Orders;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.event.PaymentRequestSettled;
@Component
public class OrderSettlementListener {
    private final Orders orders; private final PaymentOperations payments;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final java.time.Clock clock;
    OrderSettlementListener(Orders orders,PaymentOperations payments, org.springframework.context.ApplicationEventPublisher events, java.time.Clock clock) { this.orders=orders; this.payments=payments; this.events=events; this.clock=clock; }
    @ApplicationModuleListener
    public void on(PaymentRequestSettled event) {
        if (event.purpose()!=PaymentPurpose.INITIAL_ORDER) { return; }
        var order=orders.lock(event.orderId()).orElseThrow(()->new IllegalStateException("Settlement order missing"));
        var request=payments.initialRequest(event.organizationId(),event.orderId());
        if (!order.getOrganizationId().equals(event.organizationId()) || !request.id().equals(event.paymentRequestId())
                || !event.paymentId().equals(request.settledPaymentId()) || request.acceptanceStatus()!=AcceptanceStatus.CLOSED
                || !request.currency().equals(event.currency()) || request.expectedAmount().compareTo(event.amount())!=0) {
            throw new IllegalStateException("Settlement contract inconsistent");
        }
        if (order.confirmInitialPayment(event.amount(),event.currency(),event.occurredAt())) {
            events.publishEvent(new sn.groupia.ordering.api.event.OrderCommerciallyAccepted(java.util.UUID.randomUUID(),
                    clock.instant(),order.getOrganizationId(),order.getCampaignId(),order.getId()));
        } else {
            if (order.getStatus()!=sn.groupia.ordering.domain.model.OrderStatus.CONFIRMED) {
                LoggerFactory.getLogger(getClass()).error("Settlement cannot confirm ineligible order {}",event.orderId());
            }
        }
    }
}
