package sn.groupia.payment.infrastructure.provider.saspay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class SaspayWebhookAuthentication {
    static boolean verify(byte[] body, Map<String, String> headers, String secret, Clock clock) {
        try {
            var signature = header(headers, "X-Webhook-Signature");
            var timestamp = header(headers, "X-Webhook-Timestamp");
            if (secret == null
                    || secret.isBlank()
                    || signature == null
                    || !signature.matches("[0-9a-f]{64}")
                    || timestamp == null
                    || !timestamp.matches("[0-9]{1,12}")) return false;
            long age = clock.instant().getEpochSecond() - Long.parseLong(timestamp);
            if (age < -300 || age > 300) return false;
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            return MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(signature));
        } catch (Exception ex) {
            return false;
        }
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
