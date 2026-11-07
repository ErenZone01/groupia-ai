package sn.groupia.payment.infrastructure.provider.saspay;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("groupia.payment.saspay")
public record SaspayProperties(
        boolean enabled,
        URI apiUrl,
        String apiKey,
        String webhookSecret,
        UUID merchantId,
        Set<String> checkoutHosts,
        Duration connectTimeout,
        Duration readTimeout) {
    public SaspayProperties {
        if (apiUrl == null) apiUrl = URI.create("https://api.saspay.me/api/v1");
        if (checkoutHosts == null) checkoutHosts = Set.of();
        checkoutHosts = Set.copyOf(checkoutHosts);
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(3);
        if (readTimeout == null) readTimeout = Duration.ofSeconds(10);
        if (enabled) {
            if (!apiUrl.equals(URI.create("https://api.saspay.me/api/v1")))
                throw new IllegalArgumentException("SasPay requires its documented HTTPS API");
            // First slice is test-only: production and payouts have no activation path.
            if (apiKey == null
                    || !apiKey.matches("sk_test_[A-Za-z0-9_-]+")
                    || webhookSecret == null
                    || webhookSecret.isBlank()
                    || merchantId == null)
                throw new IllegalArgumentException(
                        "SasPay test key, merchant ID and webhook secret required");
            if (connectTimeout.isNegative()
                    || connectTimeout.isZero()
                    || connectTimeout.toSeconds() > 30
                    || readTimeout.isNegative()
                    || readTimeout.isZero()
                    || readTimeout.toSeconds() > 60)
                throw new IllegalArgumentException("Invalid SasPay timeouts");
            if (checkoutHosts.stream()
                    .anyMatch(h -> !h.matches("[a-z0-9]+(?:[.-][a-z0-9]+)*\\.[a-z]{2,}")))
                throw new IllegalArgumentException("Invalid SasPay checkout host allowlist");
        }
    }

    @Override
    public String toString() {
        return "SaspayProperties[enabled=" + enabled + ", credentials=redacted]";
    }
}
