package sn.groupia.identity.infrastructure.security;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("groupia.security.session")
public record SessionSettings(
        @DefaultValue("30d") Duration absoluteTtl,
        @DefaultValue("7d") Duration idleTtl,
        @DefaultValue("15s") Duration rotationGrace,
        String refreshPepperBase64,
        @DefaultValue("true") boolean cookieSecure,
        List<String> allowedOrigins,
        @DefaultValue("30") int refreshLimit,
        @DefaultValue("1m") Duration refreshWindow) {
    public SessionSettings {
        if (absoluteTtl == null
                || absoluteTtl.compareTo(Duration.ofMinutes(1)) < 0
                || absoluteTtl.compareTo(Duration.ofDays(90)) > 0
                || idleTtl == null
                || idleTtl.compareTo(Duration.ofMinutes(1)) < 0
                || idleTtl.compareTo(absoluteTtl) > 0
                || rotationGrace == null
                || rotationGrace.compareTo(Duration.ofSeconds(1)) < 0
                || rotationGrace.compareTo(Duration.ofSeconds(30)) > 0
                || refreshLimit < 1
                || refreshLimit > 300
                || refreshWindow == null
                || refreshWindow.compareTo(Duration.ofSeconds(1)) < 0
                || refreshWindow.compareTo(Duration.ofMinutes(15)) > 0)
            throw new IllegalArgumentException("Invalid session duration or quota");
        if (allowedOrigins == null || allowedOrigins.isEmpty())
            throw new IllegalArgumentException("Explicit session origins required");
        for (String origin : allowedOrigins) {
            URI u;
            try {
                u = URI.create(origin);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid session origin");
            }
            if (u.getHost() == null
                    || u.getRawUserInfo() != null
                    || u.getRawQuery() != null
                    || u.getRawFragment() != null
                    || !u.getRawPath().isEmpty()
                    || !Set.of("http", "https").contains(u.getScheme())
                    || origin.contains("*"))
                throw new IllegalArgumentException("Exact HTTP origins required");
            if (!cookieSecure
                    && !(u.getScheme().equals("http")
                            && Set.of("localhost", "127.0.0.1", "[::1]").contains(u.getHost())))
                throw new IllegalArgumentException(
                        "Insecure cookies are limited to HTTP loopback development");
        }
        allowedOrigins = List.copyOf(allowedOrigins);
    }

    public String cookieName() {
        return cookieSecure ? "__Secure-groupia_refresh" : "groupia_refresh";
    }

    @Override
    public String toString() {
        return "SessionSettings[REDACTED]";
    }
}
