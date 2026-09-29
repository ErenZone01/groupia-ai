package sn.groupia.notification.infrastructure.whatsapp;

import java.net.http.HttpClient;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.*;
import sn.groupia.notification.api.*;
import sn.groupia.notification.api.NotificationContentSource.LinkContent;

/** Outgoing template messages only; no provider-specific concepts escape notification. */
public final class MetaWhatsAppDeliveryGateway implements DeliveryGateway {
    private static final Logger LOG = LoggerFactory.getLogger(MetaWhatsAppDeliveryGateway.class);
    private final WhatsAppProperties properties;
    private final RestClient client;
    private final ObjectMapper json;

    MetaWhatsAppDeliveryGateway(WhatsAppProperties properties, RestClient client, ObjectMapper json) {
        this.properties = properties;
        this.client = client;
        this.json = json;
    }
    static RestClient http(WhatsAppProperties properties) {
        var transport = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().baseUrl("https://graph.facebook.com").requestFactory(factory).build();
    }
    @Override
    public DeliveryResult sendOtp(UUID challengeId, String phone, String code) {
        if (code == null || !code.matches("[0-9]{6}")) throw failure(challengeId, 0, null);
        var otp = properties.templates().otp();
        // Meta AUTHENTICATION copy-code uses the same code in the BODY and URL button.
        var components = List.of(
                Map.of("type", "body", "parameters", List.of(text(code))),
                Map.of("type", "button", "sub_type", "url", "index", "0", "parameters", List.of(text(code))));
        return send(challengeId, phone, otp.name(), otp.language(), components);
    }
    @Override
    public DeliveryResult sendLink(UUID deliveryId, NotificationType type, LinkContent content) {
        var template = properties.templates().forType(type);
        if (template == null) throw failure(deliveryId, 0, null);
        var components = new ArrayList<Map<String, Object>>();
        addTextComponent(components, "header", template.headerParameters(), content, deliveryId);
        addTextComponent(components, "body", template.bodyParameters(), content, deliveryId);
        if (template.urlButtonIndex() != null) {
            if (content.url() == null || !content.url().startsWith(template.urlPrefix())
                    || content.url().length() <= template.urlPrefix().length()) throw failure(deliveryId, 0, null);
            components.add(Map.of("type", "button", "sub_type", "url", "index", template.urlButtonIndex().toString(),
                    "parameters", List.of(text(content.url().substring(template.urlPrefix().length())))));
        }
        return send(deliveryId, content.phone(), template.name(), template.language(), components);
    }
    private void addTextComponent(List<Map<String, Object>> components, String type, List<WhatsAppProperties.Source> sources,
                                  LinkContent content, UUID deliveryId) {
        var parameters = new ArrayList<Map<String, String>>();
        for (var source : sources) {
            String value = switch (source) {
                case ORDER_NUMBER -> content.orderNumber();
                case MAGIC_URL -> content.url();
            };
            if (value == null || value.isBlank()) throw failure(deliveryId, 0, null);
            parameters.add(text(value));
        }
        if (!parameters.isEmpty()) components.add(Map.of("type", type, "parameters", parameters));
    }
    private DeliveryResult send(UUID id, String phone, String name, String language, Object components) {
        // Already normalized by PhoneNumbers. Only remove E.164's leading '+' for the transport.
        if (phone == null || !phone.matches("\\+[1-9][0-9]{1,14}")) throw failure(id, 0, null);
        var body = Map.of("messaging_product", "whatsapp", "recipient_type", "individual",
                "to", phone.substring(1), "type", "template",
                "template", Map.of("name", name, "language", Map.of("code", language), "components", components));
        try {
            return client.post().uri("/{version}/{phoneNumberId}/messages",
                            properties.graphApiVersion(), properties.phoneNumberId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.accessToken())
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.USER_AGENT, "Groupia").body(body).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        byte[] bytes = response.getBody().readNBytes(65537);
                        if (bytes.length > 65536) throw failure(id, status, null);
                        JsonNode document;
                        try { document = json.readTree(bytes); }
                        catch (RuntimeException malformed) { throw failure(id, status, null); }
                        if (status < 200 || status >= 300) throw failure(id, status, document);
                        var messageId = document == null ? null : document.path("messages").path(0).path("id");
                        if (messageId == null || !messageId.isString() || messageId.asText().isBlank()
                                || messageId.asText().length() > 512) throw failure(id, status, null);
                        return new DeliveryResult("META_WHATSAPP", messageId.asText());
                    });
        } catch (WhatsAppDeliveryException safe) { throw safe; }
        catch (RuntimeException transport) { throw failure(id, 0, null); }
    }
    private static Map<String, String> text(String value) { return Map.of("type", "text", "text", value); }
    private static WhatsAppDeliveryException failure(UUID id, int status, JsonNode response) {
        var error = response == null ? null : response.path("error");
        // Never log provider text, fbtrace, body, phone, headers or transport exception.
        LOG.warn("code=WHATSAPP_DELIVERY_FAILED deliveryId={} httpStatus={} metaCode={} metaSubcode={}",
                id, status, integer(error, "code"), integer(error, "error_subcode"));
        return new WhatsAppDeliveryException();
    }
    private static Long integer(JsonNode node, String field) {
        if (node == null) return null;
        var value = node.path(field);
        return value.isIntegralNumber() && value.canConvertToLong() ? value.asLong() : null;
    }
}
