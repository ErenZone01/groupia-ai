package sn.groupia.ordering.application.service;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.grouping.api.*;
import sn.groupia.grouping.api.dto.CampaignLifecycleView;
import sn.groupia.ordering.api.dto.*;
import sn.groupia.ordering.api.dto.ShippingSettlementView.Status;
import sn.groupia.ordering.domain.model.*;
import sn.groupia.ordering.domain.repository.*;
import sn.groupia.organization.api.*;
import sn.groupia.shared.api.ApiException;
@Service @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class OrderFulfillment {
    private final Orders orders;private final FulfillmentEvents events;private final ShippingFinance finance;
    private final OrganizationAccess access;private final CampaignDistribution distribution;private final SupplierCampaignLifecycle campaigns;private final Clock clock;
    OrderFulfillment(Orders orders,FulfillmentEvents events,ShippingFinance finance,OrganizationAccess access,
        CampaignDistribution distribution,SupplierCampaignLifecycle campaigns,Clock clock){
        this.orders=orders;this.events=events;this.finance=finance;this.access=access;this.distribution=distribution;this.campaigns=campaigns;this.clock=clock;
    }
    public FulfillmentBatch list(UUID org,UUID campaign){
        access.requirePermission(org,OrganizationPermission.VIEW_ORDERS);
        var c=campaigns.read(org,campaign);boolean manage=canManage(org);
        var history=events.forCampaign(org,campaign).stream().collect(Collectors.groupingBy(FulfillmentEvent::orderId));
        var rows=orders.forDistribution(org,campaign).stream().map(o->row(o,c,manage,history.getOrDefault(o.getId(),List.of()))).toList();
        var counts=new FulfillmentBatch.FulfillmentCounts(rows.size(),
            rows.stream().filter(r->r.fulfillmentStatus().equals("NOT_READY")).count(),
            rows.stream().filter(r->r.shippingSettlement().status()==Status.BALANCE_DUE||r.shippingSettlement().paymentInProgress()).count(),
            rows.stream().filter(r->r.fulfillmentStatus().equals("READY_FOR_PICKUP")).count(),
            rows.stream().filter(r->r.fulfillmentStatus().equals("PICKED_UP")).count(),
            rows.stream().filter(r->r.shippingSettlement().status()==Status.CREDIT_DUE).count(),
            rows.stream().filter(r->r.fulfillmentStatus().equals("NOT_READY")&&r.readinessBlocker()==null).count());
        return new FulfillmentBatch(c,manage,counts,rows,access.hasPermission(org,OrganizationPermission.MANAGE_REFUNDS));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public FulfillmentBatch change(UUID org,UUID campaign,UUID id,long version,FulfillmentEvent.Type type,boolean confirmed,String note){
        var actor=access.requireActorPermission(org,OrganizationPermission.MANAGE_FULFILLMENT);
        var c=distribution.lockForDistribution(org,campaign);
        if(!arrived(c))throw conflict("FULFILLMENT_CAMPAIGN_NOT_ARRIVED","Le groupage doit être arrivé avant la distribution.");
        var found=orders.find(org,id).filter(o->o.getCampaignId().equals(campaign)).orElseThrow(OrderFulfillment::missing);
        var order=orders.lock(found.getId()).orElseThrow(OrderFulfillment::missing);
        if(!order.getOrganizationId().equals(org)||!order.getCampaignId().equals(campaign))throw missing();
        order.requireVersion(version);
        String clean=note==null||note.isBlank()?null:note.strip();
        if(clean!=null&&(clean.length()>1000||clean.codePoints().anyMatch(Character::isISOControl)))
            throw new ApiException(400,"INVALID_FULFILLMENT_NOTE","Le motif doit contenir au maximum 1 000 caractères sans caractères de contrôle.");
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(type==FulfillmentEvent.Type.READINESS_REVOKED){
            if(clean==null)throw new ApiException(400,"FULFILLMENT_REASON_REQUIRED","Un motif est nécessaire pour retirer la mise à disposition.");
            order.revokeReadiness();
        }else{
            if(!confirmed)throw new ApiException(400,"FULFILLMENT_CONFIRMATION_REQUIRED","La confirmation explicite de l'équipe est nécessaire.");
            var position=finance.lockPosition(order);
            String block=blocker(order,c,position);
            if(block!=null)throw conflict(block,"Le transport doit être finalisé et sans dette ni paiement en cours avant la remise.");
            if(type==FulfillmentEvent.Type.READY_FOR_PICKUP){order.readyForPickup(now);distribution.startDistribution(org,campaign,now);}
            else if(type==FulfillmentEvent.Type.PICKED_UP)order.pickUp(now,position.status()==Status.SETTLED);
            else throw new IllegalArgumentException("Unsupported fulfillment action");
        }
        orders.flush();
        events.append(new FulfillmentEvent(UUID.randomUUID(),org,campaign,id,type,actor,now,clean));
        return list(org,campaign);
    }
    private FulfillmentRow row(Order o,CampaignLifecycleView c,boolean manage,List<FulfillmentEvent> history){
        var position=finance.read(o);String block=blocker(o,c,position);var contact=o.getCustomerContactSnapshot();
        return new FulfillmentRow(o.getId(),o.getOrderNumber(),contact.firstName()+" "+contact.lastName(),o.getStatus().name(),o.getFulfillmentStatus().name(),
            o.getVersion(),o.getReadyForPickupAt(),o.getPickedUpAt(),position,block,
            manage&&block==null&&o.getFulfillmentStatus()==FulfillmentStatus.NOT_READY,
            manage&&arrived(c)&&o.getStatus()==OrderStatus.CONFIRMED&&o.getFulfillmentStatus()==FulfillmentStatus.READY_FOR_PICKUP,
            manage&&block==null&&o.getFulfillmentStatus()==FulfillmentStatus.READY_FOR_PICKUP,
            history.stream().map(e->new FulfillmentEventView(e.id(),e.type().name(),e.actorId(),e.recordedAt(),e.note())).toList());
    }
    private static String blocker(Order o,CampaignLifecycleView c,ShippingSettlementView p){
        if(o.getStatus()!=OrderStatus.CONFIRMED)return "FULFILLMENT_ORDER_NOT_CONFIRMED";
        if(!arrived(c))return "FULFILLMENT_CAMPAIGN_NOT_ARRIVED";
        if(p.paymentInProgress())return "SHIPPING_BALANCE_PAYMENT_IN_PROGRESS";
        if(p.status()==Status.NOT_FINALIZED)return "FULFILLMENT_SHIPPING_NOT_FINALIZED";
        if(p.status()==Status.BALANCE_DUE)return "FULFILLMENT_SHIPPING_BALANCE_DUE";
        return null;
    }
    private boolean canManage(UUID org){
        return access.hasPermission(org,OrganizationPermission.MANAGE_FULFILLMENT);
    }
    private static boolean arrived(CampaignLifecycleView c){return Set.of("ARRIVED","DISTRIBUTING").contains(c.status());}
    private static ApiException conflict(String code,String text){return new ApiException(409,code,text);}
    private static ApiException missing(){return new ApiException(404,"ORDER_NOT_FOUND","Commande introuvable dans ce groupage.");}
}
