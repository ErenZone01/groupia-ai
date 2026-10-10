package sn.groupia.identity.infrastructure.hardening;

import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sn.groupia.identity.application.service.PhoneNumberNormalizer;
import sn.groupia.identity.domain.repository.AuthLimitBuckets;
import sn.groupia.shared.api.ApiException;

@Component
@EnableConfigurationProperties(AuthLimitSettings.class)
public class AuthRateLimits {
    private final AuthLimitSettings settings;
    private final AuthLimitBuckets buckets;
    private final PhoneNumberNormalizer phones;
    private final byte[] key;

    public AuthRateLimits(
            AuthLimitSettings settings, AuthLimitBuckets buckets, PhoneNumberNormalizer phones) {
        this.settings = settings;
        this.buckets = buckets;
        this.phones = phones;
        try {
            key = Base64.getDecoder().decode(settings.keyBase64());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Authentication rate limit key must be Base64");
        }
        if (key.length < 32)
            throw new IllegalArgumentException("Authentication rate limit key requires 256 bits");
    }

    public void network(String operation, String address) {
        consume(
                operation + "-network",
                address == null ? "unknown" : address,
                operation.equals("login") ? settings.loginNetwork() : settings.registerNetwork());
    }

    public void subject(String operation, String input) {
        String normalized;
        try {
            normalized = phones.normalize(input);
        } catch (ApiException invalid) {
            normalized = "invalid";
        }
        consume(
                operation + "-subject",
                normalized,
                operation.equals("login") ? settings.loginSubject() : settings.registerSubject());
    }

    private void consume(String scope, String value, int limit) {
        if (!settings.enabled()) return;
        if (!buckets.consume(
                scope, hash(scope, value), limit, settings.window(), settings.maxBuckets()))
            throw new ApiException(
                    429,
                    "AUTH_RATE_LIMITED",
                    "Trop de tentatives. Réessayez plus tard.",
                    Map.of("retryAfterSeconds", settings.window().toSeconds()));
    }

    private String hash(String scope, String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(
                            mac.doFinal((scope + "\0" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC unavailable");
        }
    }

    @Scheduled(
            fixedDelayString = "${groupia.security.auth-rate-limit.cleanup-delay:60000}",
            initialDelayString = "${groupia.security.auth-rate-limit.cleanup-delay:60000}")
    public void cleanup() {
        if (settings.enabled()) buckets.cleanup();
    }
}
