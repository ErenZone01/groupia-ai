package sn.groupia.payment.infrastructure.provider.bictorys;
import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import sn.groupia.payment.domain.model.Refund;
import sn.groupia.payment.domain.service.*;
/** Sandbox payout adapter. Initiation responses are never settlement evidence. */
public final class BictorysRefundProvider implements RefundProvider {
    private final BictorysPayoutProperties properties;
    private final String webhookSecret;
    private final RestClient http;
    private final ObjectMapper json=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    public BictorysRefundProvider(BictorysPayoutProperties p,String secret,RestClient http){
        properties=p;webhookSecret=secret;this.http=http;
        if(p.enabled()&&(secret==null||secret.isBlank()))throw new IllegalArgumentException("Payout webhook secret required");
    }
    static RestClient client(BictorysProperties base,BictorysPayoutProperties p){
        var transport=HttpClient.newBuilder().connectTimeout(base.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory=new JdkClientHttpRequestFactory(transport);factory.setReadTimeout(p.readTimeout());
        return RestClient.builder().baseUrl(base.apiUrl().toString()).requestFactory(factory)
            .defaultHeader("X-API-Key",p.privateKey()==null?"":p.privateKey()).defaultHeader("Accept","application/json").build();
    }
    public boolean enabled(){return properties.enabled();}
    public String initiate(Refund r){
        if(!enabled())throw new BictorysException("PAYOUT_DISABLED");
        String method=switch(r.externalMethod()){case "WAVE"->"wave_money";case "ORANGE_MONEY"->"orange_money";default->throw new BictorysException("PAYOUT_METHOD_INVALID");};
        var body=new BictorysPayoutRequest(r.amount(),"XOF","SN",
            new BictorysPayoutRequest.Customer(r.recipientNameSnapshot(),r.recipientPhoneSnapshot(),"SN","fr-FR"),
            "refund","Restitution du trop-perçu transport",r.merchantReference(),new BictorysPayoutRequest.Merchant(properties.merchantSecretCode()));
        var bytes=execute(http.post().uri("/pay/v1/payouts?payment_type="+method).header("idempotency-key",r.providerIdempotencyKey().toString())
            .contentType(MediaType.APPLICATION_JSON).body(body),true);
        var root=root(bytes);
        String id=BictorysWebhookParser.identifier(root,"id");
        if(id==null)throw new BictorysException("PAYOUT_AMBIGUOUS_RESPONSE");
        return id;
    }
    public RefundObservation verify(Refund r){
        if(!enabled())throw new BictorysException("PAYOUT_DISABLED");
        if(r.providerTransactionId()==null)throw new BictorysException("PAYOUT_REFERENCE_UNAVAILABLE");
        return parse(execute(http.get().uri("/pay/v1/transactions/{id}",r.providerTransactionId()),false));
    }
    private byte[] execute(RestClient.RequestHeadersSpec<?> request,boolean initiation){
        try{return request.exchange((sent,response)->{
            int status=response.getStatusCode().value();
            if(initiation?!Set.of(200,201,202).contains(status):status!=200)throw new BictorysException("PAYOUT_HTTP_"+status);
            byte[] body=response.getBody().readNBytes(65537);
            if(body.length>65536)throw new BictorysException("PAYOUT_RESPONSE_TOO_LARGE");
            return body;
        });}catch(BictorysException ex){throw ex;}catch(RuntimeException ex){throw new BictorysException("PAYOUT_TRANSPORT_UNCERTAIN");}
    }
    private JsonNode root(byte[] payload){
        try{var n=json.readTree(payload);if(n==null||!n.isObject())throw new IllegalArgumentException();return n;}
        catch(RuntimeException ex){throw new BictorysException("PAYOUT_INVALID_JSON");}
    }
    public boolean recognizes(byte[] payload){
        try{
            var n=root(payload);var data=n.path("data");
            // A charge reference with an outgoing type is contradictory evidence:
            // retain the incoming parser's rejection, never turn it into a payment observation.
            if(n.path("merchantReference").asText("").startsWith("GP-")||data.path("merchantReference").asText("").startsWith("GP-"))return false;
            return payoutMarker(n)||payoutMarker(data)||payoutReference(n)||payoutReference(data);
        }catch(RuntimeException ex){return false;}
    }
    private boolean payoutReference(JsonNode n){return n.path("merchantReference").isTextual()&&n.path("merchantReference").asText().startsWith("GPR-");}
    private boolean payoutMarker(JsonNode n){
        return Set.of("refund","payout").contains(n.path("transactionType").asText(""))
            ||Set.of("refund","payout").contains(n.path("type").asText(""))
            ||n.path("event").asText("").startsWith("payout.")||n.path("event").asText("").startsWith("refund.");
    }
    public boolean authentic(Map<String,String> headers){
        String supplied=headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("X-Secret-Key")).map(Map.Entry::getValue).findFirst().orElse("");
        return webhookSecret!=null&&!webhookSecret.isBlank()&&MessageDigest.isEqual(webhookSecret.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8));
    }
    public RefundObservation parse(byte[] payload){
        var root=root(payload);var n=root;
        if(root.has("data")){
            if(!root.get("data").isObject()||!payoutMarker(root)||root.has("id")||root.has("merchantReference"))throw new BictorysException("PAYOUT_UNKNOWN_ENVELOPE");
            n=root.get("data");
        }
        // A reference only routes the receipt. Direction and recipient remain mandatory proof.
        if(!payoutMarker(root)&&!payoutMarker(n))throw new BictorysException("PAYOUT_DIRECTION_REQUIRED");
        if(n.has("transactionType")&&!Set.of("refund","payout").contains(n.path("transactionType").asText()))throw new BictorysException("PAYOUT_DIRECTION_MISMATCH");
        if(n.has("type")&&!Set.of("refund","payout").contains(n.path("type").asText()))throw new BictorysException("PAYOUT_DIRECTION_MISMATCH");
        String id=BictorysWebhookParser.identifier(n,"id"),reference=BictorysWebhookParser.text(n,"merchantReference");
        String currency=BictorysWebhookParser.text(n,"currency");
        String phone=BictorysWebhookParser.text(n,"customerPhone");
        if(phone==null&&n.path("customerObject").isObject())phone=BictorysWebhookParser.text(n.path("customerObject"),"phone");
        if(phone!=null&&phone.matches("221[0-9]{9}"))phone="+"+phone;
        if(n.path("customerObject").has("phone")){
            String second=BictorysWebhookParser.text(n.path("customerObject"),"phone");
            if(second!=null&&second.matches("221[0-9]{9}"))second="+"+second;
            if(!Objects.equals(phone,second))throw new BictorysException("PAYOUT_RECIPIENT_MISMATCH");
        }
        if(id==null||reference==null||currency==null||phone==null||!phone.matches("\\+221[0-9]{9}")||!n.path("amount").isNumber())
            throw new BictorysException("PAYOUT_INCOMPLETE_EVIDENCE");
        var amount=n.path("amount").decimalValue().abs();
        if(amount.signum()<=0||amount.stripTrailingZeros().scale()>0)throw new BictorysException("PAYOUT_INVALID_AMOUNT");
        String status;
        if(n.path("status").isIntegralNumber()&&n.path("status").decimalValue().signum()==0)status="SUCCEEDED";
        else if(n.path("status").isTextual())status=switch(n.path("status").asText().toLowerCase(Locale.ROOT)){
            case "succeeded","successful","success"->"SUCCEEDED";case "pending","processing"->"PENDING";
            case "failed"->"FAILED";case "cancelled","canceled"->"CANCELLED";default->throw new BictorysException("PAYOUT_UNKNOWN_STATUS");};
        else throw new BictorysException("PAYOUT_UNKNOWN_STATUS");
        if(root.has("event")){
            String event=BictorysWebhookParser.text(root,"event");
            String suffix=event==null?"":event.substring(event.indexOf('.')+1);
            String declared=switch(suffix){case "succeeded","successful"->"SUCCEEDED";case "pending"->"PENDING";case "failed"->"FAILED";case "cancelled"->"CANCELLED";default->"UNKNOWN";};
            if(!status.equals(declared))throw new BictorysException("PAYOUT_EVENT_MISMATCH");
        }
        Instant at=null;String timestamp=BictorysWebhookParser.text(n,"timestamp");
        if(timestamp!=null){try{at=Instant.parse(timestamp);}catch(RuntimeException ex){throw new BictorysException("PAYOUT_INVALID_TIMESTAMP");}}
        return new RefundObservation("BICTORYS","sandbox",reference,id,amount,currency,phone,status,at);
    }
}

