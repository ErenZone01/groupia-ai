package sn.groupia.procurement.application.service;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import sn.groupia.grouping.api.SupplierCampaignLifecycle;
import sn.groupia.ordering.api.SupplierOrdering;
import sn.groupia.organization.api.*;
import sn.groupia.procurement.api.dto.*;
import sn.groupia.procurement.domain.model.SupplierOrder;
import sn.groupia.procurement.domain.repository.SupplierOrders;
import sn.groupia.shared.api.ApiException;
@Service
public class SupplierPurchases {
    private final SupplierCampaignLifecycle grouping;
    private final SupplierOrdering ordering;
    private final OrganizationAccess access;
    private final SupplierOrders purchases;
    private final Clock clock;
    SupplierPurchases(SupplierCampaignLifecycle grouping,SupplierOrdering ordering,OrganizationAccess access,SupplierOrders purchases,Clock clock) {
        this.grouping=grouping;this.ordering=ordering;this.access=access;this.purchases=purchases;this.clock=clock;
    }
    @Transactional
    public SupplierOrderResponse create(UUID org,UUID campaign,long expectedVersion) {
        UUID actor=access.requireActorPermission(org,OrganizationPermission.MANAGE_GROUPINGS);
        access.requirePermission(org,OrganizationPermission.VIEW_ORDERS);
        var current=grouping.lock(org,campaign); // first lock, same as checkout admission; CLOSED prevents new orders.
        var previous=purchases.find(org,campaign);
        if(previous.isPresent()) {
            var p=previous.get();
            // The original campaign version identifies the same creation intention, including concurrent retries.
            if(p.sourceCampaignVersion()!=expectedVersion)
                throw new ApiException(409,"SUPPLIER_ORDER_ALREADY_EXISTS","Une commande fournisseur existe déjà.");
            if("CLOSED".equals(current.status())) throw new IllegalStateException("Inconsistent supplier lifecycle");
            return new SupplierOrderResponse(p.view(),current,purchases.anomalyCount(p.id()),true);
        }
        if(!"CLOSED".equals(current.status())) throw new ApiException(409,"INVALID_GROUPING_TRANSITION","Fermez le groupage avant de commander.");
        if(current.version()!=expectedVersion) throw new ApiException(409,"GROUPING_CAMPAIGN_VERSION_CONFLICT","Le groupage a changé. Actualisez-le.");
        var projection=ordering.capture(org,campaign); // row locks serialize confirmation and expiry until commit.
        if(projection.activePendingOrderCount()>0)
            throw new ApiException(409,"SUPPLIER_ORDER_PENDING_PAYMENTS","Des commandes attendent leur paiement.",
                    Map.of("pendingOrderCount",projection.activePendingOrderCount()));
        var purchase=SupplierOrder.capture(org,expectedVersion,actor,clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),projection);
        purchases.insert(purchase);
        var ordered=grouping.ordered(org,campaign,expectedVersion,purchase.orderedAt());
        return new SupplierOrderResponse(purchase.view(),ordered,0,false);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public SupplierOrderResponse read(UUID org,UUID campaign) {
        var current=grouping.read(org,campaign); // authorization and scoped ownership
        var p=purchases.find(org,campaign).orElseThrow(()->new ApiException(404,"SUPPLIER_ORDER_NOT_FOUND","Aucune commande fournisseur."));
        return new SupplierOrderResponse(p.view(),current,purchases.anomalyCount(p.id()),false);
    }
}
