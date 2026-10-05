package sn.groupia.ordering.application.service;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.ordering.domain.model.*;
import sn.groupia.ordering.domain.repository.Orders;
import sn.groupia.ordering.api.dto.*;
import sn.groupia.organization.api.*;
import sn.groupia.payment.api.RefundOperations;
import sn.groupia.payment.api.dto.*;
import sn.groupia.shared.api.ApiException;
@Service @Transactional(readOnly=true)
public class OrderCredits {
    private final Orders orders;private final ShippingFinance finance;private final OrganizationAccess access;private final RefundOperations refunds;
    OrderCredits(Orders o,ShippingFinance f,OrganizationAccess a,RefundOperations r){orders=o;finance=f;access=a;refunds=r;}
    public record CreditResolution(ShippingSettlementView settlement,boolean canManage,boolean canResolve,boolean payoutsEnabled,List<RefundView> refunds){}
    public CreditResolution read(UUID org,UUID id){
        access.requirePermission(org,OrganizationPermission.VIEW_PAYMENTS);
        var order=find(org,id);var position=finance.read(order);
        boolean manage=access.hasPermission(org,OrganizationPermission.MANAGE_REFUNDS);
        return new CreditResolution(position,manage,manage&&order.getFulfillmentStatus()==FulfillmentStatus.PICKED_UP
            &&position.status()==ShippingSettlementView.Status.CREDIT_DUE&&!position.paymentInProgress()&&refunds.history(org,id).isEmpty(),
            refunds.payoutsEnabled(),refunds.history(org,id));
    }
    @Transactional
    public RefundView reserve(UUID org,UUID id,long version,String method,String external,String reference,String note,boolean confirmed){
        UUID actor=access.requireActorPermission(org,OrganizationPermission.MANAGE_REFUNDS);
        var found=find(org,id);var order=orders.lock(found.getId()).orElseThrow(OrderCredits::missing);
        if(!order.getOrganizationId().equals(org))throw missing();
        if(order.getFulfillmentStatus()!=FulfillmentStatus.PICKED_UP)throw new ApiException(409,"CREDIT_NOT_STABLE_FOR_REFUND","Le crédit pourra être régularisé après la remise de la commande.");
        if(!confirmed)throw new ApiException(400,"REFUND_CONFIRMATION_REQUIRED","La confirmation de la restitution est obligatoire.");
        if(!Set.of("BICTORYS_PAYOUT","MANUAL_EXTERNAL").contains(method)||!Set.of("WAVE","ORANGE_MONEY","CASH","BANK_TRANSFER","OTHER").contains(external))
            throw new ApiException(400,"REFUND_METHOD_INVALID","Méthode de régularisation invalide.");
        reference=clean(reference,160);note=clean(note,1000);
        if(method.equals("BICTORYS_PAYOUT")){
            if(!refunds.payoutsEnabled())throw new ApiException(503,"PAYOUT_DISABLED","Le remboursement Bictorys est désactivé.");
            if(!Set.of("WAVE","ORANGE_MONEY").contains(external)||reference!=null)throw new ApiException(400,"REFUND_METHOD_INVALID","Moyen de remboursement invalide.");
        }else if(external.equals("CASH")?note==null:reference==null)
            throw new ApiException(400,"REFUND_EVIDENCE_REQUIRED",external.equals("CASH")?"Une note de confirmation est obligatoire pour les espèces.":"Une référence externe est obligatoire.");
        var position=finance.lockPosition(order);
        var history=refunds.history(org,id);
        var existing=history.stream().findFirst();
        if(existing.isEmpty()){
            order.requireVersion(version);
            if(order.getStatus()!=OrderStatus.CONFIRMED||position.status()!=ShippingSettlementView.Status.CREDIT_DUE||position.paymentInProgress())
                throw new ApiException(409,"SHIPPING_CREDIT_NOT_DUE","Aucun crédit stable ne peut être régularisé.");
        }
        var contact=order.getCustomerContactSnapshot();
        if(method.equals("BICTORYS_PAYOUT")&&!contact.phoneE164().matches("\\+221[0-9]{9}"))throw new ApiException(409,"REFUND_RECIPIENT_UNSUPPORTED","Le remboursement automatique exige le numéro sénégalais de la commande.");
        return refunds.reserve(new ReserveRefund(org,id,position.currentShippingCalculationId(),
            existing.map(RefundView::amount).orElse(position.creditAmount()),order.getCurrency(),contact.phoneE164(),
            contact.firstName()+" "+contact.lastName(),actor,method,external,reference,note));
    }
    private Order find(UUID org,UUID id){return orders.find(org,id).orElseThrow(OrderCredits::missing);}
    private static ApiException missing(){return new ApiException(404,"ORDER_NOT_FOUND","Commande introuvable.");}
    private static String clean(String text,int max){
        if(text==null||text.isBlank())return null;
        text=text.strip();
        if(text.length()>max||text.codePoints().anyMatch(Character::isISOControl))throw new ApiException(400,"REFUND_EVIDENCE_INVALID","Référence ou note invalide.");
        return text;
    }
}

