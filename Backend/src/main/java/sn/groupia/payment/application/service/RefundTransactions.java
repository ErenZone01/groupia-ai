package sn.groupia.payment.application.service;
import java.time.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.api.event.ShippingCreditRefundSettled;
import sn.groupia.payment.domain.model.Refund;
import sn.groupia.payment.domain.repository.RefundStore;
import sn.groupia.payment.domain.service.*;
import sn.groupia.shared.api.ApiException;
@Service @Transactional
public class RefundTransactions {
    private final RefundStore store;private final Clock clock;private final ApplicationEventPublisher events;
    RefundTransactions(RefundStore store,Clock clock,ApplicationEventPublisher events){this.store=store;this.clock=clock;this.events=events;}
    @Transactional(propagation=Propagation.MANDATORY)
    public RefundView reserve(ReserveRefund c){
        store.coordinate("order:"+c.orderId());
        var existing=store.forOrder(c.organizationId(),c.orderId());
        if(existing.isPresent()){
            var r=existing.get();
            if(!r.method().equals(c.method())||!r.externalMethod().equals(c.externalMethod())||!Objects.equals(r.manualReference(),c.reference())||!Objects.equals(r.note(),c.note()))
                throw new ApiException(409,"REFUND_ALREADY_EXISTS","Une régularisation existe déjà pour ce crédit.");
            return r.view();
        }
        if(!"XOF".equals(c.currency())||c.amount().signum()<=0||c.amount().stripTrailingZeros().scale()>0)throw new IllegalArgumentException("Invalid refund money");
        var r=store.create(c,now());
        if(c.method().equals("MANUAL_EXTERNAL"))succeed(r,null,null);
        return store.get(r.id()).orElseThrow().view();
    }
    public boolean claim(UUID org,UUID id){
        var r=store.lock(id);scope(r,org);
        if(!r.method().equals("BICTORYS_PAYOUT")||!r.status().equals("CREATED"))return false;
        store.state(id,"PENDING",true,"PAYOUT_INITIATION_IN_PROGRESS",null,null,null);
        return true;
    }
    public void initiated(UUID id,String transaction){
        store.coordinate("transaction:"+transaction);
        var r=store.lock(id);
        if(r.succeeded())return;
        if(r.providerTransactionId()!=null&&!r.providerTransactionId().equals(transaction)){review(r,"PAYOUT_TRANSACTION_MISMATCH");return;}
        if(store.transactionTaken(r.provider(),r.providerAccountReference(),transaction,id)){review(r,"PAYOUT_TRANSACTION_ALREADY_USED");return;}
        // A concurrently received final webhook has priority over the HTTP initiation response.
        if(!r.status().equals("PENDING")||!"PAYOUT_INITIATION_IN_PROGRESS".equals(r.reconciliationReason()))return;
        store.state(id,"PENDING",false,null,transaction,null,null);
    }
    public void uncertain(UUID id,String reason){var r=store.lock(id);if(!r.succeeded()&&r.status().equals("PENDING"))review(r,reason);}
    public UUID receive(String hash,boolean authentic,RefundObservation observation,String error){
        return store.receipt(hash,authentic,observation,error,now());
    }
    public void apply(UUID receipt,UUID expected,RefundObservation o){
        store.coordinate("transaction:"+o.transactionId());
        var found=expected==null?store.byReference(o.merchantReference()):store.get(expected);
        if(found.isEmpty()){if(receipt!=null)store.receiptResult(receipt,null,"REVIEW_REQUIRED","UNKNOWN_REFERENCE");return;}
        var r=store.lock(found.get().id());
        String error=correlation(r,o,expected);
        if(error!=null){if(!r.succeeded())review(r,error);if(receipt!=null)store.receiptResult(receipt,r.id(),"REVIEW_REQUIRED",error);return;}
        if(!r.succeeded()){
            if(o.status().equals("SUCCEEDED"))succeed(r,o.transactionId(),o.occurredAt());
            else if(o.status().equals("PENDING")){
                if(!Set.of("FAILED","CANCELLED","REVIEW_REQUIRED").contains(r.status()))store.state(r.id(),"PENDING",false,null,o.transactionId(),o.occurredAt(),null);
            }else{
                // Non-success without an ordering timestamp cannot prove a definitive failure.
                if(o.occurredAt()==null||(r.providerOccurredAt()!=null&&o.occurredAt().isBefore(r.providerOccurredAt())))review(r,"PAYOUT_STATE_UNCERTAIN");
                else store.state(r.id(),o.status(),false,null,o.transactionId(),o.occurredAt(),null);
            }
        }
        if(receipt!=null)store.receiptResult(receipt,r.id(),"PROCESSED",null);
    }
    private String correlation(Refund r,RefundObservation o,UUID expected){
        if(!r.merchantReference().equals(o.merchantReference()))return "PAYOUT_REFERENCE_MISMATCH";
        if(expected!=null&&!r.id().equals(expected))return "PAYOUT_REFERENCE_MISMATCH";
        if(!r.method().equals("BICTORYS_PAYOUT")||!r.provider().equals(o.provider())||!r.providerAccountReference().equals(o.account()))return "PAYOUT_PROVIDER_MISMATCH";
        if(!r.currency().equals(o.currency()))return "PAYOUT_CURRENCY_MISMATCH";
        if(r.amount().compareTo(o.amount())!=0)return "PAYOUT_AMOUNT_MISMATCH";
        if(!r.recipientPhoneSnapshot().equals(o.recipientPhone()))return "PAYOUT_RECIPIENT_MISMATCH";
        if(r.providerTransactionId()!=null&&!r.providerTransactionId().equals(o.transactionId()))return "PAYOUT_TRANSACTION_MISMATCH";
        if(store.transactionTaken(o.provider(),o.account(),o.transactionId(),r.id()))return "PAYOUT_TRANSACTION_ALREADY_USED";
        return null;
    }
    private void succeed(Refund r,String transaction,Instant providerAt){
        store.state(r.id(),"SUCCEEDED",false,null,transaction,providerAt,now());
        events.publishEvent(new ShippingCreditRefundSettled(UUID.randomUUID(),now(),r.organizationId(),r.orderId(),r.id(),r.amount(),r.currency()));
    }
    private void review(Refund r,String reason){store.state(r.id(),"REVIEW_REQUIRED",true,reason,null,null,null);}
    public Refund require(UUID org,UUID id){var r=store.get(id).orElseThrow(()->new ApiException(404,"REFUND_NOT_FOUND","Régularisation introuvable."));scope(r,org);return r;}
    public List<RefundView> history(UUID org,UUID order){return store.history(org,order).stream().map(Refund::view).toList();}
    public List<RefundReviewView> queue(UUID org){return store.reviewQueue(org).stream().map(r->new RefundReviewView(r.orderId(),r.view(),r.reconciliationReason())).toList();}
    private void scope(Refund r,UUID org){if(!r.organizationId().equals(org))throw new ApiException(404,"REFUND_NOT_FOUND","Régularisation introuvable.");}
    private Instant now(){return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
}

