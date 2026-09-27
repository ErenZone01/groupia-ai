package sn.groupia.ordering.application.service;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.ordering.api.SupplierOrdering;
import sn.groupia.ordering.api.dto.SupplierProjection;
import sn.groupia.ordering.application.query.SupplierSummaryReadModel;
import sn.groupia.organization.api.*;
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class SupplierOrderingService implements SupplierOrdering {
    private final SupplierSummaryReadModel summaries;
    private final OrganizationAccess access;
    private final Clock clock;
    SupplierOrderingService(SupplierSummaryReadModel summaries, OrganizationAccess access, Clock clock) {
        this.summaries=summaries; this.access=access; this.clock=clock;
    }
    public SupplierProjection capture(UUID org,UUID campaign) {
        access.requirePermission(org,OrganizationPermission.VIEW_ORDERS);
        summaries.lockOrders(org,campaign);
        return summaries.projection(org,campaign,clock.instant());
    }
}
