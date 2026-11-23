package sn.groupia.payment.infrastructure.provider.saspay;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One unambiguous JSON object; no arbitrary parser message or payload is retained. */
public final class SaspayWebhookJson {
    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();

    private SaspayWebhookJson() {}

    public static JsonNode parse(byte[] payload) {
        JsonNode root = read(payload);
        if (!root.isObject() || !root.path("data").isObject())
            throw new SaspayException("SASPAY_INVALID_RESPONSE");
        return root;
    }

    static JsonNode read(byte[] payload) {
        JsonNode root;
        try {
            root = JSON.readTree(payload);
        } catch (RuntimeException ex) {
            throw new SaspayException("SASPAY_INVALID_JSON");
        }
        if (root == null || (!root.isObject() && !root.isArray()))
            throw new SaspayException("SASPAY_INVALID_RESPONSE");
        return root;
    }
}
