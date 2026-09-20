package sn.groupia.identity.infrastructure.security;

import java.time.Duration;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("groupia.security.jwt")
record JwtSettings(@NotBlank String secretBase64, @NotBlank String issuer,
        @NotBlank String audience, @NotNull Duration accessTokenTtl) {
    @Override public String toString() { return "JwtSettings[REDACTED]"; }
}
