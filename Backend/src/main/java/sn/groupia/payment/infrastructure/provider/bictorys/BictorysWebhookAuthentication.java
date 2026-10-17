package sn.groupia.payment.infrastructure.provider.bictorys;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authenticates the exact wire bytes; partial or invalid HMAC never falls back to static auth. */
final class BictorysWebhookAuthentication {
    private BictorysWebhookAuthentication() {}

    static boolean verify(byte[] body, Map<String, String> headers, String secret) {
        return verify(body, headers, secret, Clock.systemUTC());
    }

    static boolean verify(byte[] body, Map<String, String> headers, String secret, Clock clock) {
        if (secret == null || secret.isBlank()) return false;
        try {
            String signature = single(headers, "X-Webhook-Signature");
            String timestamp = single(headers, "X-Webhook-Timestamp");
            String staticKey = single(headers, "X-Secret-Key");
            if (signature != null || timestamp != null) {
                if (signature == null
                        || timestamp == null
                        || !signature.matches("[0-9a-fA-F]{64}")
                        || !timestamp.matches("[0-9]{1,16}")) return false;
                long instant = Long.parseLong(timestamp);
                long now = clock.millis();
                if (instant < now - 300_000 || instant > now + 300_000) return false;
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
                return MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(signature));
            }
            return staticKey != null
                    && MessageDigest.isEqual(
                            secret.getBytes(StandardCharsets.UTF_8),
                            staticKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception invalid) {
            return false;
        }
    }

    private static String single(Map<String, String> headers, String name) {
        var matches =
                headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).toList();
        if (matches.size() > 1)
            throw new IllegalArgumentException("Ambiguous authentication headers");
        return matches.isEmpty() ? null : matches.getFirst().getValue();
    }
}
