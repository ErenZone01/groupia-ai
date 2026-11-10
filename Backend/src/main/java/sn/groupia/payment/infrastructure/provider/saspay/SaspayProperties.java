package sn.groupia.payment.infrastructure.provider.saspay;

import java.math.BigDecimal;
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
        Duration readTimeout,
        boolean livePilotEnabled,
        UUID livePilotOrganizationId,
        BigDecimal livePilotMaxChargedXof) {
    public SaspayProperties {
        if (apiUrl == null) apiUrl = URI.create("https://api.saspay.me/api/v1");
        if (checkoutHosts == null) checkoutHosts = Set.of();
        checkoutHosts = Set.copyOf(checkoutHosts);
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(3);
        if (readTimeout == null) readTimeout = Duration.ofSeconds(10);
        if (livePilotMaxChargedXof == null) livePilotMaxChargedXof = new BigDecimal("2000");
        if (enabled) {
            if (!apiUrl.equals(URI.create("https://api.saspay.me/api/v1")))
                throw new IllegalArgumentException("SasPay requires its documented HTTPS API");
            boolean testKey = apiKey != null && apiKey.matches("sk_test_[A-Za-z0-9_-]+");
            boolean liveKey = apiKey != null && apiKey.matches("sk_live_[A-Za-z0-9_-]+");
            if ((!testKey && !liveKey)
                    || webhookSecret == null
                    || webhookSecret.isBlank()
                    || merchantId == null)
                throw new IllegalArgumentException(
                        "SasPay key, merchant ID and webhook secret required");
            if (liveKey) {
                // Scoped pilot only. No permanent marketplace authorization is implied.
                if (!livePilotEnabled
                        || livePilotOrganizationId == null
                        || livePilotMaxChargedXof.signum() <= 0
                        || livePilotMaxChargedXof.stripTrailingZeros().scale() > 0
                        || livePilotMaxChargedXof.compareTo(new BigDecimal("5000")) > 0
                        || checkoutHosts.isEmpty())
                    throw new IllegalArgumentException(
                            "SasPay live pilot requires a single organization and a <=5000 XOF cap");
            } else if (livePilotEnabled) {
                throw new IllegalArgumentException("SasPay live pilot requires a live key");
            }
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

    public boolean isLive() {
        return enabled && apiKey != null && apiKey.startsWith("sk_live_");
    }

    @Override
    public String toString() {
        return "SaspayProperties[enabled=" + enabled + ", credentials=redacted]";
    }
}
