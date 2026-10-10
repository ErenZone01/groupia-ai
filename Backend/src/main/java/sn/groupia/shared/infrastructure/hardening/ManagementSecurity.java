package sn.groupia.shared.infrastructure.hardening;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class ManagementSecurity {
    @Bean
    @Order(0)
    SecurityFilterChain managementSecurityChain(
            HttpSecurity http, @Value("${groupia.monitoring.token:}") String token)
            throws Exception {
        if (!token.isEmpty() && token.length() < 32)
            throw new IllegalArgumentException(
                    "Monitoring token must contain at least 32 characters");
        return http.securityMatcher("/actuator/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .httpBasic(c -> c.disable())
                .formLogin(c -> c.disable())
                .logout(c -> c.disable())
                .headers(h -> h.httpStrictTransportSecurity(x -> x.disable()))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers(
                                                HttpMethod.GET,
                                                "/actuator/health",
                                                "/actuator/health/liveness",
                                                "/actuator/health/readiness")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/actuator/metrics",
                                                "/actuator/metrics/**")
                                        .access(
                                                (authentication, context) ->
                                                        new AuthorizationDecision(
                                                                !token.isEmpty()
                                                                        && MessageDigest.isEqual(
                                                                                ("Bearer " + token)
                                                                                        .getBytes(
                                                                                                StandardCharsets
                                                                                                        .UTF_8),
                                                                                java.util.Objects
                                                                                        .toString(
                                                                                                context.getRequest()
                                                                                                        .getHeader(
                                                                                                                "Authorization"),
                                                                                                "")
                                                                                        .getBytes(
                                                                                                StandardCharsets
                                                                                                        .UTF_8))))
                                        .anyRequest()
                                        .denyAll())
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint((req, res, ex) -> res.sendError(401))
                                        .accessDeniedHandler((req, res, ex) -> res.sendError(403)))
                .build();
    }
}
