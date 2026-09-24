package sn.groupia.payment.application.service;
import org.slf4j.LoggerFactory;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.payment.api.PaymentStatus;
import sn.groupia.payment.domain.service.*;
import sn.groupia.shared.api.ApiException;

@Service @Transactional(propagation=Propagation.NEVER)
public class PaymentWebhooks {
    private final ProviderRegistry providers;
    private final tools.jackson.databind.ObjectMapper json;
    private final PaymentTransactions transactions;
    PaymentWebhooks(ProviderRegistry providers,PaymentTransactions transactions,tools.jackson.databind.ObjectMapper json) {
        this.providers=providers; this.transactions=transactions; this.json=json;
    }
    public UUID receive(String provider,byte[] payload,Map<String,String> headers) {
        if (payload.length>65536) { throw new ApiException(413,"PAYMENT_WEBHOOK_TOO_LARGE","Réception trop volumineuse."); }
        var adapter=providers.require(provider);
        String fingerprint=hash(payload);
        boolean authentic;
        try { authentic=adapter.verifyAuthenticity(payload,headers); }
        catch (RuntimeException ex) { authentic=false; }
        NormalizedWebhook event=null;
        String parsingError=null;
        if (authentic) {
            try { event=adapter.parse(payload); }
            catch (RuntimeException failure) { parsingError=WebhookParsingFailure.safeCode(failure); }
            if (parsingError==null && (event==null || event.observation()==null)) { parsingError=WebhookParsingFailure.FALLBACK_CODE; }
        }
        String eventId=event==null?null:event.providerEventId();
        if (eventId!=null && (eventId.isBlank() || eventId.length()>160)) { parsingError="WEBHOOK_INVALID_EVENT_ID"; }
        if (parsingError!=null) { eventId=null; event=null; }
        String evidence=authentic && eventId!=null?"id:"+eventId:"hash:"+fingerprint;
        String dedup=hash(json.writeValueAsBytes(Arrays.asList(adapter.name(),adapter.accountReference(),authentic,evidence)));
        UUID id=null;
        try {
            id=transactions.receive(adapter.name(),adapter.accountReference(),eventId,dedup,fingerprint,authentic,event==null?null:event.observation(),parsingError);
        } finally {
            if (parsingError!=null) {
                LoggerFactory.getLogger(getClass()).warn("Webhook parsing failed provider={} receiptId={} code={} shape={}",
                        adapter.name(),id,parsingError,WebhookParsingDiagnostics.inspect(payload));
            }
        }
        if (!authentic) { throw new ApiException(401,"PAYMENT_AUTHENTICITY_FAILED","Authenticité du webhook invalide."); }
        if (parsingError!=null) { return id; }
        retry(id);
        return id;
    }
    public void retry(UUID id) {
        var work=transactions.prepareWebhook(id);
        if (!work.pending()) { return; }
        ProviderObservation proof=null; boolean failed=false;
        if (work.observation()!=null && work.observation().status()==PaymentStatus.SUCCEEDED && work.instruction()!=null) {
            try { proof=providers.require(work.provider()).verify(work.instruction().withTransactionId(work.observation().providerTransactionId())); }
            catch (RuntimeException ex) { failed=true; }
        }
        try { transactions.processWebhook(id,proof,failed); }
        catch(RuntimeException ex) {
            // The financial transaction has rolled back; persist the retry separately.
            try { transactions.webhookFailure(id); }
            catch(RuntimeException unavailable) {
                LoggerFactory.getLogger(getClass()).error("Webhook {} retry state could not be persisted",id);
            }
            throw ex;
        }
    }
    public void retryPending() {
        for(var id:transactions.retryableWebhooks(100)) {
            try { retry(id); }
            catch(RuntimeException ex) { LoggerFactory.getLogger(getClass()).error("Webhook {} processing failed; receipt retained",id); }
        }
    }
    private String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
