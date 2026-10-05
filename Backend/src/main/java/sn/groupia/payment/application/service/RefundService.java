package sn.groupia.payment.application.service;
import java.util.*;
import java.security.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.domain.service.*;
import sn.groupia.organization.api.*;
import sn.groupia.shared.api.ApiException;
@Service
public class RefundService implements RefundOperations {
    private final RefundTransactions transactions;private final RefundProvider provider;private final OrganizationAccess access;
    RefundService(RefundTransactions t,RefundProvider p,OrganizationAccess a){transactions=t;provider=p;access=a;}
    @Transactional(propagation=Propagation.MANDATORY)
    public RefundView reserve(ReserveRefund c){return transactions.reserve(c);}
    @Transactional(propagation=Propagation.NEVER)
    public void dispatch(UUID org,UUID id){
        if(!transactions.claim(org,id))return;
        var refund=transactions.require(org,id);
        try{String transaction=provider.initiate(refund);transactions.initiated(id,transaction);}
        catch(RuntimeException ex){transactions.uncertain(id,"PAYOUT_INITIATION_UNCERTAIN");}
    }
    public List<RefundView> history(UUID org,UUID order){return transactions.history(org,order);}
    public RefundView get(UUID org,UUID id){return transactions.require(org,id).view();}
    public boolean payoutsEnabled(){return provider.enabled();}
    @Transactional(propagation=Propagation.NEVER)
    public void reconcile(UUID org,UUID id){
        access.requirePermission(org,OrganizationPermission.MANAGE_REFUNDS);
        var refund=transactions.require(org,id);
        if(!refund.method().equals("BICTORYS_PAYOUT")||refund.succeeded())return;
        try{var proof=provider.verify(refund);transactions.apply(null,id,proof);}
        catch(RuntimeException ex){transactions.uncertain(id,"PAYOUT_RECONCILIATION_UNCERTAIN");}
    }
    public List<RefundReviewView> queue(UUID org){access.requirePermission(org,OrganizationPermission.VIEW_PAYMENTS);return transactions.queue(org);}
    public boolean recognizes(String name,byte[] payload){return "BICTORYS".equalsIgnoreCase(name)&&provider.recognizes(payload);}
    @Transactional(propagation=Propagation.NEVER)
    public UUID receive(byte[] payload,Map<String,String> headers){
        boolean authentic=provider.authentic(headers);RefundObservation observation=null;String error=null;
        if(authentic){try{observation=provider.parse(payload);}catch(RuntimeException ex){error="PAYOUT_INVALID_EVIDENCE";}}
        else error="PAYMENT_AUTHENTICITY_FAILED";
        UUID receipt=transactions.receive(hash(payload),authentic,observation,error);
        if(!authentic)throw new ApiException(401,"PAYMENT_AUTHENTICITY_FAILED","Authenticité du webhook invalide.");
        if(observation!=null)transactions.apply(receipt,null,observation);
        return receipt;
    }
    private String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}

