package sn.groupia.procurement.application.service;
import org.springframework.stereotype.Component;
import org.springframework.modulith.events.ApplicationModuleListener;
import sn.groupia.ordering.api.event.OrderCommerciallyAccepted;
import sn.groupia.procurement.domain.repository.SupplierOrders;
@Component
public class LateAcceptanceListener {
    private final SupplierOrders purchases;
    LateAcceptanceListener(SupplierOrders purchases){this.purchases=purchases;}
    @ApplicationModuleListener
    public void on(OrderCommerciallyAccepted event) {
        purchases.flagLateAcceptance(event.organizationId(),event.campaignId(),event.orderId(),event.eventId(),event.occurredAt());
    }
}
