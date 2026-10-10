package sn.groupia.identity.infrastructure.hardening;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("groupia.security.auth-rate-limit")
public record AuthLimitSettings(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean networkEnabled,
        String keyBase64,
        @DefaultValue("15m") Duration window,
        @DefaultValue("60") int loginNetwork,
        @DefaultValue("10") int loginSubject,
        @DefaultValue("20") int registerNetwork,
        @DefaultValue("5") int registerSubject,
        @DefaultValue("100000") int maxBuckets) {
    public AuthLimitSettings {
        if (window == null
                || window.toSeconds() < 1
                || window.compareTo(Duration.ofHours(24)) > 0
                || loginNetwork < 1
                || loginSubject < 1
                || registerNetwork < 1
                || registerSubject < 1
                || maxBuckets < 4
                || maxBuckets > 1000000)
            throw new IllegalArgumentException("Invalid authentication rate limit configuration");
    }

    @Override
    public String toString() {
        return "AuthLimitSettings[redacted]";
    }
}
