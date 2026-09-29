package sn.groupia.customeraccess.application.service;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import sn.groupia.ordering.api.event.OrderCommerciallyAccepted;
@Component @ConditionalOnProperty(name="groupia.customer-access.enabled",havingValue="true")
class ConfirmedOrderAccess {
    private final CustomerAccess access;
    ConfirmedOrderAccess(CustomerAccess access){this.access=access;}
    @ApplicationModuleListener public void on(OrderCommerciallyAccepted event){access.confirmed(event.organizationId(),event.orderId());}
}
