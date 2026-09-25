package sn.groupia.identity.infrastructure.security;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import sn.groupia.identity.application.service.AccessTokens;

@Component
class JwtAccessTokens implements AccessTokens {
    private final JwtEncoder encoder;
    private final JwtSettings settings;
    private final Clock clock;

    JwtAccessTokens(JwtEncoder encoder, JwtSettings settings, Clock clock) {
        this.encoder = encoder;
        this.settings = settings;
        this.clock = clock;
    }

    @Override public AccessToken issue(UUID userAccountId) {
        var now = clock.instant();
        var claims = JwtClaimsSet.builder().issuer(settings.issuer())
                .subject(userAccountId.toString()).audience(List.of(settings.audience()))
                .issuedAt(now).expiresAt(now.plus(settings.accessTokenTtl()))
                .id(UUID.randomUUID().toString()).build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
        return new AccessToken(token, "Bearer", settings.accessTokenTtl().toSeconds());
    }
}
