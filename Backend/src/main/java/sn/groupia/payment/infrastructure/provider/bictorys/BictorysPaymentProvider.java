package sn.groupia.payment.infrastructure.provider.bictorys;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import sn.groupia.payment.api.PaymentStatus;
import sn.groupia.payment.domain.service.*;
import tools.jackson.databind.JsonNode;

public final class BictorysPaymentProvider implements PaymentProvider {
    private final BictorysClient client;
    private final BictorysProperties properties;
    private final BictorysWebhookParser parser=new BictorysWebhookParser();
    BictorysPaymentProvider(BictorysClient client,BictorysProperties properties){this.client=client;this.properties=properties;}
    @Override public String name(){return "BICTORYS";}
    @Override public String accountReference(){return "sandbox";}
    @Override public WebhookSuccessEvidence webhookSuccessEvidence(){return WebhookSuccessEvidence.AUTHENTICATED_WEBHOOK;}
    @Override public ProviderObservation initiate(ProviderInstruction instruction) {
        var contact=instruction.checkout();
        if(contact==null || contact.orderNumber()==null || contact.customerName()==null || contact.phone()==null) {
            throw new BictorysException("BICTORYS_CHECKOUT_CONTEXT_REQUIRED");
        }
        var response=client.initiate(new BictorysChargeRequest(instruction.amount(),instruction.currency(),"SN",
                contact.orderNumber(),instruction.merchantReference(),
                new BictorysChargeRequest.Customer(contact.customerName(),contact.phone(),contact.email(),"SN")));
        var node=parser.root(response.body());
        String transaction=BictorysWebhookParser.identifier(node,"transactionId");
        String charge=BictorysWebhookParser.identifier(node,"chargeId");
        if(response.status()==201 && transaction==null || response.status()==202 && charge==null && transaction==null) {
            throw new BictorysException("BICTORYS_UNKNOWN_INITIATION");
        }
        String merchant=BictorysWebhookParser.text(node,"merchantReference");
        if(merchant==null){merchant=instruction.merchantReference();} // Acknowledgement, never proof of payment.
        String reference=BictorysWebhookParser.text(node,"paymentReference");
        if(reference==null){reference=contact.orderNumber();}
        String url=BictorysWebhookParser.text(node,"link"); // redirectUrl is a return destination, not proof or checkout.
        if(url!=null){validateCheckoutUrl(url);}
        var rawStatus=BictorysWebhookParser.text(node,"status");
        var state=rawStatus==null?new BictorysStatusMapper.State(PaymentStatus.PENDING,null):BictorysStatusMapper.map(rawStatus);
        // Link-only replies lack financial proof. Success is established later through the complete server evidence.
        if(state.status()==PaymentStatus.SUCCEEDED){state=new BictorysStatusMapper.State(PaymentStatus.PENDING,null);}
        return new ProviderObservation(name(),accountReference(),merchant,transaction,instruction.paymentRequestId(),instruction.orderId(),
                instruction.purpose(),node.has("amount")?node.get("amount").decimalValue():instruction.amount(),
                node.has("currency")?BictorysWebhookParser.text(node,"currency"):instruction.currency(),state.status(),null,
                new ProviderMetadata(reference,url,charge,state.review()));
    }
    @Override public ProviderObservation verify(ProviderInstruction instruction) {
        String id=instruction.providerTransactionId();
        if(id==null){throw new BictorysException("BICTORYS_TRANSACTION_ID_REQUIRED");}
        var status=parser.root(client.status(id).body());
        String rawStatus=BictorysWebhookParser.text(status,"status");
        if(rawStatus==null){throw new BictorysException("BICTORYS_INCOMPLETE_STATUS");}
        JsonNode evidence=status;
        if(!status.has("id") || !status.has("amount") || !status.has("currency") || !status.has("merchantReference")) {
            evidence=parser.root(client.details(id).body());
            if(!BictorysStatusMapper.map(rawStatus).equals(BictorysStatusMapper.map(BictorysWebhookParser.text(evidence,"status")))) {
                throw new BictorysException("BICTORYS_INCONSISTENT_STATUS");
            }
            for(String field:List.of("id","merchantReference","paymentReference","amount","currency")) {
                boolean same=status.has(field) && status.get(field).equals(evidence.get(field));
                if(field.equals("amount") && status.path(field).isNumber() && evidence.path(field).isNumber()) {
                    same=status.get(field).decimalValue().compareTo(evidence.get(field).decimalValue())==0;
                }
                if(status.has(field) && !same) {
                    throw new BictorysException("BICTORYS_INCONSISTENT_EVIDENCE");
                }
            }
        }
        var observation=parser.observation(evidence);
        if(!id.equals(observation.providerTransactionId())){throw new BictorysException("BICTORYS_INCONSISTENT_TRANSACTION");}
        return observation.correlate(instruction);
    }
    @Override public boolean verifyAuthenticity(byte[] payload,Map<String,String> headers) {
        var values=headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("X-Secret-Key")).map(Map.Entry::getValue).toList();
        if(values.size()!=1 || values.getFirst()==null){return false;}
        return MessageDigest.isEqual(properties.webhookSecret().getBytes(StandardCharsets.UTF_8),values.getFirst().getBytes(StandardCharsets.UTF_8));
    }
    @Override public NormalizedWebhook parse(byte[] payload) {
        // Documented id is the transaction id; event is an event type, not a unique delivery identifier.
        return new NormalizedWebhook(null,parser.parse(payload));
    }
    private void validateCheckoutUrl(String value) {
        try {
            var uri=URI.create(value);
            if(!"https".equals(uri.getScheme()) || !Set.of("pay.bictorys.com","pay.test.bictorys.com").contains(uri.getHost())
                    || uri.getUserInfo()!=null || uri.getFragment()!=null || (uri.getPort()!=-1 && uri.getPort()!=443)) {
                throw new IllegalArgumentException();
            }
        } catch(RuntimeException ex){throw new BictorysException("BICTORYS_INVALID_CHECKOUT_URL");}
    }
}
