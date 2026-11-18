package sn.groupia.payment.infrastructure.provider.saspay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SaspayWebhookAuthentication {
    private static final Logger log = LoggerFactory.getLogger(SaspayWebhookAuthentication.class);

    static boolean verify(byte[] body, Map<String, String> headers, String secret, Clock clock) {
        try {
            var signature = header(headers, "X-Webhook-Signature");
            var timestamp = header(headers, "X-Webhook-Timestamp");
            if (secret == null || secret.isBlank()) return reject("SIGNING_SECRET_NOT_CONFIGURED");
            if (signature == null) return reject("SIGNATURE_HEADER_MISSING_OR_DUPLICATE");
            if (!signature.matches("[0-9a-f]{64}")) return reject("INVALID_SIGNATURE_FORMAT");
            if (timestamp == null) return reject("TIMESTAMP_HEADER_MISSING_OR_DUPLICATE");
            if (!timestamp.matches("[0-9]{1,12}")) return reject("INVALID_TIMESTAMP_FORMAT");
            long age = clock.instant().getEpochSecond() - Long.parseLong(timestamp);
            if (age < -300 || age > 300) return reject("TIMESTAMP_OUTSIDE_300S_WINDOW");
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(signature)))
                return reject("HMAC_MISMATCH");
            // SasPay also supplies X-Webhook-Event. It is unsigned separately,
            // but when present it must agree with the event in the signed body.
            // Keep absence compatible with historic deliveries; reject ambiguous
            // case-variant duplicates and all mismatched event headers.
            boolean eventHeaderPresent =
                    headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("X-Webhook-Event"));
            if (eventHeaderPresent) {
                String headerEvent = header(headers, "X-Webhook-Event");
                if (headerEvent == null || !headerEvent.matches("[a-z_]+\\.[a-z_]+"))
                    return reject("INVALID_EVENT_HEADER");
                var parsed = new tools.jackson.databind.ObjectMapper().readTree(body);
                if (!parsed.path("event").isTextual()
                        || !headerEvent.equals(parsed.path("event").asText()))
                    return reject("EVENT_HEADER_MISMATCH");
            }
            return true;
        } catch (Exception ex) {
            return reject("AUTHENTICATION_PROCESSING_FAILED");
        }
    }

    private static boolean reject(String reason) {
        // Fixed reason codes only; never log secrets, headers, signature, raw body or customer
        // data.
        log.debug("SasPay webhook authentication rejected: {}", reason);
        return false;
    }

    private static String header(Map<String, String> headers, String name) {
        var values =
                headers.entrySet().stream()
                        .filter(e -> e.getKey().equalsIgnoreCase(name))
                        .map(Map.Entry::getValue)
                        .toList();
        return values.size() == 1 ? values.getFirst() : null;
    }
}
