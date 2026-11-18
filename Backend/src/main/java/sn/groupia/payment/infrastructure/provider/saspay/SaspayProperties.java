package sn.groupia.payment.infrastructure.provider.saspay;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
        BigDecimal livePilotMaxChargedXof,
        boolean productionEnabled,
        BigDecimal productionMaxChargedXof) {
    /** Compatibility constructor for existing pilot fixtures. */
    public SaspayProperties(
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
        this(
                enabled,
                apiUrl,
                apiKey,
                webhookSecret,
                merchantId,
                checkoutHosts,
                connectTimeout,
                readTimeout,
                livePilotEnabled,
                livePilotOrganizationId,
                livePilotMaxChargedXof,
                false,
                null);
    }

    @ConstructorBinding
    public SaspayProperties {
        // SasPay publishes isolated LIVE and SANDBOX hosts. A test key must never
        // be transmitted to the production endpoint, even on developer machines.
        boolean testKey = apiKey != null && apiKey.matches("sk_test_[A-Za-z0-9_-]+");
        boolean liveKey = apiKey != null && apiKey.matches("sk_live_[A-Za-z0-9_-]+");
        URI liveUrl = URI.create("https://api.saspay.me/api/v1");
        URI sandboxUrl = URI.create("https://sandbox-api.saspay.me/api/v1");
        if (apiUrl == null) apiUrl = testKey ? sandboxUrl : liveUrl;
        if (checkoutHosts == null) checkoutHosts = Set.of();
        checkoutHosts = Set.copyOf(checkoutHosts);
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(3);
        if (readTimeout == null) readTimeout = Duration.ofSeconds(10);
        if (livePilotMaxChargedXof == null) livePilotMaxChargedXof = new BigDecimal("2000");
        if (productionMaxChargedXof == null) productionMaxChargedXof = new BigDecimal("1000000");
        if (enabled) {
            if (!apiUrl.equals(liveKey ? liveUrl : sandboxUrl))
                throw new IllegalArgumentException(
                        "SasPay API environment must match the secret key environment");
            if ((!testKey && !liveKey)
                    || webhookSecret == null
                    || webhookSecret.isBlank()
                    || merchantId == null)
                throw new IllegalArgumentException(
                        "SasPay key, merchant ID and webhook secret required");
            if (liveKey) {
                if (productionEnabled) {
                    // Normal production is deliberately distinct from the old one-shop pilot.
                    // Keep a finite, server-enforced cap for every checkout.
                    if (livePilotEnabled
                            || productionMaxChargedXof.signum() <= 0
                            || productionMaxChargedXof.stripTrailingZeros().scale() > 0
                            || productionMaxChargedXof.compareTo(new BigDecimal("10000000")) > 0
                            || checkoutHosts.isEmpty())
                        throw new IllegalArgumentException(
                                "SasPay production requires an approved checkout host and a"
                                        + " positive integer XOF limit <= 10000000");
                } else if (!livePilotEnabled
                        || livePilotOrganizationId == null
                        || livePilotMaxChargedXof.signum() <= 0
                        || livePilotMaxChargedXof.stripTrailingZeros().scale() > 0
                        || livePilotMaxChargedXof.compareTo(new BigDecimal("5000")) > 0
                        || checkoutHosts.isEmpty()) {
                    throw new IllegalArgumentException(
                            "SasPay live pilot requires a single organization and a <=5000 XOF cap");
                }
            } else if (livePilotEnabled || productionEnabled) {
                throw new IllegalArgumentException(
                        "SasPay live pilot or production mode requires a live key");
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
