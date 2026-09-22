package sn.groupia.payment.application.service;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.domain.service.*;

@Service
public class PaymentService implements PaymentOperations {
    private final PaymentTransactions transactions;
    private final ProviderRegistry providers;
    PaymentService(PaymentTransactions transactions,ProviderRegistry providers) { this.transactions=transactions; this.providers=providers; }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public PaymentRequestView createRequest(CreatePaymentRequest command) { return transactions.create(command); }
    @Override public PaymentRequestView initialRequest(UUID org,UUID order) { return transactions.initial(org,order); }
    @Override public boolean cancelInitialAcceptance(UUID org,UUID order) { return transactions.cancel(org,order); }
    @Override @Transactional(propagation=Propagation.NEVER)
    public PaymentAttemptView initiateInitial(UUID org,UUID order,String provider,String key) {
        return initiateInitial(org,order,provider,key,null);
    }
    @Override @Transactional(propagation=Propagation.NEVER)
    public PaymentAttemptView initiateInitial(UUID org,UUID order,String provider,String key,PaymentCheckoutContext context) {
        var adapter=providers.require(provider);
        var claim=transactions.claim(org,order,adapter.name(),adapter.accountReference(),key,context);
        if (!claim.owned()) { return claim.receipt(); }
        ProviderObservation result;
        try { result=adapter.initiate(claim.instruction()); }
        catch (RuntimeException ex) { return transactions.uncertain(claim.instruction().paymentId()); }
        if(result!=null && result.status()==PaymentStatus.SUCCEEDED) {
            ProviderObservation proof=null;
            try { proof=adapter.verify(claim.instruction().withTransactionId(result.providerTransactionId())); }
            catch(RuntimeException ignored) { /* Durable claim remains available for reconciliation. */ }
            return transactions.verifiedInitiationResult(claim.instruction().paymentId(),result,proof);
        }
        return transactions.initiationResult(claim.instruction().paymentId(),result);
    }
    @Transactional(propagation=Propagation.NEVER)
    public PaymentAttemptView reconcile(UUID paymentId) {
        var instruction=transactions.reconciliationInstruction(paymentId);
        var adapter=providers.require(instruction.provider());
        ProviderObservation result;
        try { result=adapter.verify(instruction); }
        catch (RuntimeException ex) { return transactions.uncertain(paymentId); }
        return transactions.result(paymentId,result);
    }
    public List<UUID> reconciliationQueue(int limit) {
        if (limit<1 || limit>100) { throw new IllegalArgumentException("Limit must be 1..100"); }
        return transactions.uncertainPayments(limit);
    }
}
