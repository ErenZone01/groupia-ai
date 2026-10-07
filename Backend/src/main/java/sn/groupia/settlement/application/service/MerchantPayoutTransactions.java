package sn.groupia.settlement.application.service;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.organization.api.*;
import sn.groupia.grouping.api.MerchantCampaignFacts;
import sn.groupia.settlement.domain.model.*;
import sn.groupia.settlement.domain.repository.MerchantFinanceStore;
import sn.groupia.settlement.domain.service.MerchantPayoutProvider;
import sn.groupia.shared.api.ApiException;
@Service @Transactional
public class MerchantPayoutTransactions {
    private final MerchantFinanceStore store;
    private final MerchantBalances balances;
    private final MerchantCampaignFacts campaigns;
    private final OrganizationAccess access;
    private final MerchantPayoutProvider provider;
    private final Clock clock;
    private final boolean enabled;
    MerchantPayoutTransactions(MerchantFinanceStore s,MerchantBalances b,MerchantCampaignFacts c,OrganizationAccess a,
        MerchantPayoutProvider p,Clock clock,@Value("${groupia.settlement.payouts.enabled:false}") boolean enabled) {
        store=s;balances=b;campaigns=c;access=a;provider=p;this.clock=clock;this.enabled=enabled;
    }
    public boolean available() { return enabled&&provider.enabled(); }
    public MerchantPayout reserve(UUID org,UUID campaign,UUID key,UUID expectedDestination,boolean confirmed) {
        UUID actor=access.requireActorPermission(org,OrganizationPermission.MANAGE_SETTLEMENTS);
        if(!confirmed)throw error(400,"PAYOUT_CONFIRMATION_REQUIRED","Confirmez la demande de versement.");
        store.lockOrganization(org);
        String reference="MERCHANT_PAYOUT:"+org+":"+key;
        var old=store.byReference(org,reference);
        if(old.isPresent()) {
            if(!old.get().campaignId().equals(campaign)||!old.get().destinationId().equals(expectedDestination))
                throw error(409,"PAYOUT_IDEMPOTENCY_CONFLICT","Cette demande correspond à un autre versement.");
            return old.get();
        }
        if(!available())throw error(503,"MERCHANT_PAYOUT_DISABLED","Le versement automatique est indisponible.");
        var c=campaigns.lock(org,campaign);
        if(!c.available())throw error(409,"MERCHANT_FUNDS_NOT_AVAILABLE","Les fonds seront disponibles après la clôture des ventes.");
        if(store.payouts(org).stream().anyMatch(p->p.campaignId().equals(campaign)&&p.reserved()))
            throw error(409,"MERCHANT_PAYOUT_IN_PROGRESS","Un versement est déjà en cours pour ce groupage.");
        var d=store.destination(org).orElseThrow(()->error(409,"PAYOUT_DESTINATION_REQUIRED","Renseignez une destination de versement."));
        if(!d.id().equals(expectedDestination))throw error(409,"PAYOUT_DESTINATION_CHANGED","La destination a changé. Vérifiez-la avant de confirmer.");
        var position=balances.calculate(org,campaign,true,false,true).campaigns().stream().filter(r->r.campaignId().equals(campaign)).findFirst().orElseThrow();
        if(position.availableForPayout().signum()<=0)throw error(409,"MERCHANT_FUNDS_NOT_AVAILABLE","Aucun montant disponible au versement.");
        var p=new MerchantPayout(UUID.randomUUID(),org,campaign,position.availableForPayout(),"XOF","CREATED",d.id(),d.method(),d.phoneE164(),d.holderName(),
            provider.name(),provider.account(),reference,UUID.randomUUID(),null,actor,now(),null,null,true,"AWAITING_DISPATCH",0);
        store.create(p);return p;
    }
    public MerchantPayoutDestination destination(UUID org,UUID previous,String method,String phone,String holder,boolean confirmed) {
        UUID actor=access.requireActorPermission(org,OrganizationPermission.MANAGE_PAYOUT_DESTINATION);
        if(!confirmed)throw error(400,"PAYOUT_CONFIRMATION_REQUIRED","Confirmez la nouvelle destination.");
        if(method==null||!Set.of("WAVE","ORANGE_MONEY").contains(method))throw error(400,"PAYOUT_DESTINATION_INVALID","Choisissez Wave ou Orange Money.");
        phone=phone==null?"":phone.replaceAll("[\\s().-]","");
        if(phone.matches("[0-9]{9}"))phone="+221"+phone;
        if(phone.startsWith("00221"))phone="+"+phone.substring(2);
        if(phone.matches("221[0-9]{9}"))phone="+"+phone;
        holder=holder==null?"":holder.strip();
        if(!phone.matches("\\+221[0-9]{9}")||holder.isBlank()||holder.length()>160||holder.codePoints().anyMatch(Character::isISOControl))
            throw error(400,"PAYOUT_DESTINATION_INVALID","Vérifiez le titulaire et le numéro sénégalais.");
        store.lockOrganization(org);
        var current=store.destination(org);
        if(!Objects.equals(current.map(MerchantPayoutDestination::id).orElse(null),previous))
            throw error(409,"PAYOUT_DESTINATION_CHANGED","La destination a changé. Actualisez avant de la modifier.");
        var d=new MerchantPayoutDestination(UUID.randomUUID(),org,method,phone,holder,"ACTIVE",now(),now(),actor,actor,0);
        store.replaceDestination(d);return d;
    }
    public MerchantPayout get(UUID org,UUID id) { return store.payout(org,id); }
    public boolean claim(UUID org,UUID id) {
        store.lockOrganization(org);var p=store.payout(org,id);
        if(!p.status().equals("CREATED"))return false;
        if(!available())throw error(503,"MERCHANT_PAYOUT_DISABLED","Le versement automatique est indisponible.");
        store.state(id,"PENDING",null,null,null,true,"PAYOUT_INITIATION_IN_PROGRESS");return true;
    }
    public void initiated(UUID org,UUID id,String transaction) {
        store.lockOrganization(org);var p=store.payout(org,id);
        if(p.succeeded())return;
        if(transaction==null||!transaction.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")) { review(p,"PAYOUT_INITIATION_UNCERTAIN");return; }
        store.lockTransaction(p.provider(),p.providerAccountReference(),transaction);
        if(store.transactionTaken(p.provider(),p.providerAccountReference(),transaction,id)) { review(p,"PAYOUT_TRANSACTION_ALREADY_USED");return; }
        if(p.providerTransactionId()!=null&&!p.providerTransactionId().equals(transaction)) { review(p,"PAYOUT_TRANSACTION_MISMATCH");return; }
        if(p.status().equals("PENDING")&&"PAYOUT_INITIATION_IN_PROGRESS".equals(p.reconciliationReason()))
            store.state(id,"PENDING",transaction,null,null,false,null);
    }
    public void uncertain(UUID org,UUID id) {
        store.lockOrganization(org);var p=store.payout(org,id);if(p.reserved())review(p,"PAYOUT_PROVIDER_UNCERTAIN");
    }
    /** Only the server-side provider adapter supplies this evidence. Never exposed as an HTTP mutation. */
    public void verified(UUID org,UUID id,MerchantPayoutProvider.Proof proof) {
        store.lockOrganization(org);var p=store.payout(org,id);
        if(p.succeeded())return;
        String error=correlate(p,proof);
        if(error!=null) { review(p,error);return; }
        store.lockTransaction(p.provider(),p.providerAccountReference(),proof.transactionId());
        if(store.transactionTaken(p.provider(),p.providerAccountReference(),proof.transactionId(),id)) { review(p,"PAYOUT_TRANSACTION_ALREADY_USED");return; }
        if(proof.status().equals("SUCCEEDED")) {
            store.state(id,"SUCCEEDED",proof.transactionId(),proof.occurredAt(),now(),false,null);
            store.append(new MerchantLedgerEntry(UUID.randomUUID(),org,p.campaignId(),null,"MERCHANT_PAYOUT","DEBIT",p.amount(),p.currency(),BigDecimal.ZERO,
                "PAYOUT:"+p.id(),"MERCHANT_PAYOUT",p.id(),now()));
        } else if(proof.status().equals("PENDING")) {
            if(p.status().equals("PENDING")&&!p.reconciliationRequired()
                &&(p.providerOccurredAt()==null||proof.occurredAt()!=null&&!proof.occurredAt().isBefore(p.providerOccurredAt())))
                store.state(id,"PENDING",proof.transactionId(),proof.occurredAt(),null,false,null);
        } else if(Set.of("FAILED","CANCELLED").contains(proof.status())&&proof.occurredAt()!=null
            &&(p.providerOccurredAt()==null||!proof.occurredAt().isBefore(p.providerOccurredAt()))) {
            store.state(id,proof.status(),proof.transactionId(),proof.occurredAt(),null,false,null);
        } else review(p,"PAYOUT_STATE_UNCERTAIN");
    }
    private String correlate(MerchantPayout p,MerchantPayoutProvider.Proof e) {
        if(e==null||!e.authenticated())return "PAYOUT_PROOF_UNAUTHENTICATED";
        if(!p.provider().equals(e.provider())||!p.providerAccountReference().equals(e.account()))return "PAYOUT_PROVIDER_MISMATCH";
        if(!p.businessReference().equals(e.businessReference()))return "PAYOUT_REFERENCE_MISMATCH";
        if(e.amount()==null||p.amount().compareTo(e.amount())!=0)return "PAYOUT_AMOUNT_MISMATCH";
        if(!p.currency().equals(e.currency()))return "PAYOUT_CURRENCY_MISMATCH";
        if(!p.method().equals(e.method())||!p.phoneE164().equals(e.phoneE164()))return "PAYOUT_DESTINATION_MISMATCH";
        if(e.transactionId()==null||!e.transactionId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")||
            (p.providerTransactionId()!=null&&!p.providerTransactionId().equals(e.transactionId())))return "PAYOUT_TRANSACTION_MISMATCH";
        if(e.status()==null)return "PAYOUT_STATE_UNCERTAIN";
        return null;
    }
    private void review(MerchantPayout p,String reason) { store.state(p.id(),"REVIEW_REQUIRED",null,null,null,true,reason); }
    private Instant now() { return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private static ApiException error(int status,String code,String message) { return new ApiException(status,code,message); }
}
