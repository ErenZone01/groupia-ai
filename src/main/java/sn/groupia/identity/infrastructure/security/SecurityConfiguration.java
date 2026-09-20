package sn.groupia.identity.infrastructure.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;
import sn.groupia.shared.api.ApiException;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtSettings.class)
class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecretKey jwtSigningKey(JwtSettings settings) {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(settings.secretBase64()); }
        catch (IllegalArgumentException ex) { throw new IllegalStateException("JWT signing key must be Base64 encoded."); }
        if (bytes.length < 32) { throw new IllegalStateException("JWT signing key must contain at least 256 bits."); }
        if (settings.accessTokenTtl().compareTo(Duration.ofMinutes(1)) < 0
                || settings.accessTokenTtl().compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalStateException("JWT lifetime must be between one minute and one hour.");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, JwtSettings settings) {
        var decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(30)),
                new JwtIssuerValidator(settings.issuer()),
                new JwtClaimValidator<List<String>>("aud", audiences -> audiences != null && audiences.contains(settings.audience())),
                new JwtClaimValidator<Instant>("exp", Objects::nonNull),
                new JwtClaimValidator<String>("sub", SecurityConfiguration::isUuid)));
        return decoder;
    }

    private static boolean isUuid(String subject) {
        try { return subject != null && UUID.fromString(subject).toString().equals(subject); }
        catch (IllegalArgumentException ex) { return false; }
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
        var unauthorized = (org.springframework.security.web.AuthenticationEntryPoint) (request, response, ex) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            resolver.resolveException(request, response, null,
                    new ApiException(401, "AUTHENTICATION_REQUIRED", "Une authentification valide est requise."));
        };
        var forbidden = (org.springframework.security.web.access.AccessDeniedHandler) (request, response, ex) ->
                resolver.resolveException(request, response, null,
                        new ApiException(403, "ACCESS_DENIED", "Cette opération est interdite."));
        return http
                .csrf(csrf -> csrf.disable()) // Stateless bearer headers only; no cookie authentication.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/groupings/*", "/api/public/orders/track").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/public/groupings/*/checkout", "/api/public/orders/*/payments", "/api/payment/webhooks/*").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> {})
                        .authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .build();
    }
}
