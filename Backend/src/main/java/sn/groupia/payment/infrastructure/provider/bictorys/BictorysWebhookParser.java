package sn.groupia.payment.infrastructure.provider.bictorys;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.slf4j.LoggerFactory;
import sn.groupia.payment.api.PaymentStatus;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import sn.groupia.payment.domain.service.*;
final class BictorysWebhookParser {
    private final ObjectMapper json=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    JsonNode root(byte[] body) {
        try {
            var root=json.readTree(body);
            if(root==null || !root.isObject()){throw new IllegalArgumentException();}
            return root;
        } catch(RuntimeException ex){throw new BictorysException("BICTORYS_INVALID_JSON");}
    }
    JsonNode transactionNode(byte[] body) {
        var root=root(body);
        if(root.has("data")) {
            if(!"charge.successful".equals(text(root,"event")) || !root.get("data").isObject()
                    || root.has("id") || root.has("merchantReference")) {throw new BictorysException("BICTORYS_UNKNOWN_ENVELOPE");}
            return root.get("data");
        }
        return root;
    }
    ProviderObservation parse(byte[] body) {return observation(transactionNode(body),true);}
    ProviderObservation observation(JsonNode node) {return observation(node,false);}
    private ProviderObservation observation(JsonNode node,boolean webhook) {
        if(node.has("transactionType") && !"payment".equals(text(node,"transactionType")))
            throw new BictorysException("BICTORYS_UNSUPPORTED_EVENT");
        String id=identifier(node,"id"),status=text(node,"status"),currency=text(node,"currency");
        if(id==null || status==null || currency==null || !node.path("amount").isNumber()) {
            throw new BictorysException("BICTORYS_INCOMPLETE_EVIDENCE");
        }
        String type=text(node,"type");
        if(type!=null && !type.equals("payment") && !type.equals("mobile_money")) {
            throw new BictorysException("BICTORYS_UNSUPPORTED_EVENT");
        }
        var state=BictorysStatusMapper.map(status);
        Instant occurred=null;
        String timestamp=text(node,"timestamp");
        if(timestamp!=null) {
            try {occurred=Instant.parse(timestamp);}
            catch(DateTimeParseException ex) {
                if(!webhook || state.status()!=PaymentStatus.SUCCEEDED) {
                    throw new BictorysException("BICTORYS_INVALID_TIMESTAMP");
                }
                // Receipt authenticity and all financial references must still be validated before settlement.
                LoggerFactory.getLogger(BictorysWebhookParser.class).warn(
                        "provider=BICTORYS code=BICTORYS_INVALID_TIMESTAMP action=IGNORED_FOR_AUTHENTICATED_SUCCESS");
            }
        }
        String reference=text(node,"paymentReference");
        // Historical data.reference is a display reference only, never a merchantReference fallback.
        if(reference==null && (!webhook || state.status()!=PaymentStatus.SUCCEEDED)){reference=text(node,"reference");}
        return new ProviderObservation("BICTORYS","sandbox",text(node,"merchantReference"),id,null,null,null,
                node.get("amount").decimalValue(),currency,state.status(),occurred,
                new ProviderMetadata(reference,null,null,state.review()));
    }
    static String text(JsonNode node,String field) {
        var value=node.get(field);
        if(value==null || value.isNull()){return null;}
        if(!value.isTextual() || value.asText().isBlank() || value.asText().length()>4096) {
            throw new BictorysException("BICTORYS_INVALID_FIELD");
        }
        return value.asText();
    }
    static String identifier(JsonNode node,String field) {
        var value=node.get(field);
        if(value==null || value.isNull()){return null;}
        String id=value.isIntegralNumber()?value.asText():text(node,field);
        if(id==null || !id.matches("[A-Za-z0-9_-]{1,160}")){throw new BictorysException("BICTORYS_INVALID_IDENTIFIER");}
        return id;
    }
}
