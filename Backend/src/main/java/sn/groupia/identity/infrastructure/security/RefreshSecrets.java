package sn.groupia.identity.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(SessionSettings.class)
public class RefreshSecrets {
    private final byte[] pepper;
    private final SecureRandom random = new SecureRandom();

    public RefreshSecrets(SessionSettings settings, Environment environment) {
        try {
            pepper = Base64.getDecoder().decode(settings.refreshPepperBase64());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Refresh pepper must be explicit Base64");
        }
        if (pepper.length < 32)
            throw new IllegalArgumentException("Refresh pepper requires at least 256 bits");
        for (String property :
                List.of(
                        "groupia.security.jwt.secret-base64",
                        "groupia.security.auth-rate-limit.key-base64",
                        "groupia.customer-access.magic-key-base64",
                        "groupia.customer-access.otp-pepper-base64")) {
            String value = environment.getProperty(property);
            if (value != null && !value.isBlank()) {
                byte[] other;
                try {
                    other = Base64.getDecoder().decode(value);
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("Invalid configured security key");
                }
                if (MessageDigest.isEqual(pepper, other))
                    throw new IllegalArgumentException("Refresh pepper must be independent");
            }
        }
        if ((environment.matchesProfiles("prod")
                        || environment.getProperty(
                                "groupia.security.production", Boolean.class, false))
                && (!settings.cookieSecure()
                        || settings.allowedOrigins().stream()
                                .anyMatch(o -> !o.startsWith("https://"))))
            throw new IllegalArgumentException(
                    "Production sessions require Secure cookies and HTTPS origins");
    }

    public String generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public byte[] tokenHash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            if (decoded.length != 32
                    || !Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(decoded)
                            .equals(token)) return null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
        return hmac(token);
    }

    public byte[] quotaKey(UUID session) {
        return hmac("refresh-session\0" + session);
    }

    private byte[] hmac(String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable");
        }
    }

    @Override
    public String toString() {
        return "RefreshSecrets[REDACTED]";
    }
}
