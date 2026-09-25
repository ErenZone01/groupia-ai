package sn.groupia.ordering.application.service;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.ordering.domain.model.OrderStatus;
import sn.groupia.ordering.domain.repository.Orders;
import sn.groupia.payment.api.PaymentOperations;
@Service
public class OrderExpirationTransaction {
    private final Orders orders; private final PaymentOperations payments; private final Clock clock;
    OrderExpirationTransaction(Orders orders,PaymentOperations payments,Clock clock) { this.orders=orders; this.payments=payments; this.clock=clock; }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean expire(UUID id) {
        var order=orders.lock(id).orElseThrow();
        if (order.getStatus()!=OrderStatus.PENDING_PAYMENT || clock.instant().isBefore(order.getPaymentDeadline())) { return false; }
        if (!payments.cancelInitialAcceptance(order.getOrganizationId(),id)) { return false; }
        return order.expire(clock.instant());
    }
}
