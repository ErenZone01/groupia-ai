package sn.groupia.ordering.application.service;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.modulith.events.ApplicationModuleListener;
import sn.groupia.ordering.domain.repository.Orders;
import sn.groupia.ordering.api.dto.ShippingSettlementView;
import sn.groupia.payment.api.RefundOperations;
import sn.groupia.payment.api.event.ShippingCreditRefundSettled;
@Component
public class OrderRefundListener {
    private final Orders orders;private final RefundOperations refunds;private final ShippingFinance finance;private final Clock clock;
    OrderRefundListener(Orders o,RefundOperations r,ShippingFinance f,Clock c){orders=o;refunds=r;finance=f;clock=c;}
    @ApplicationModuleListener
    public void on(ShippingCreditRefundSettled event){
        var order=orders.lock(event.orderId()).orElseThrow();
        if(!order.getOrganizationId().equals(event.organizationId()))throw new IllegalStateException("Refund organization mismatch");
        var refund=refunds.history(event.organizationId(),event.orderId()).stream().filter(r->r.id().equals(event.refundId())).findFirst().orElseThrow();
        if(!refund.status().equals("SUCCEEDED")||refund.amount().compareTo(event.amount())!=0||!refund.currency().equals(event.currency()))
            throw new IllegalStateException("Refund settlement mismatch");
        var position=finance.lockPosition(order);
        if(position.status()!=ShippingSettlementView.Status.SETTLED)throw new IllegalStateException("Refund did not settle shipping credit");
        order.completeAfterCreditResolution(clock.instant());
    }
}

