package sn.groupia.customeraccess.infrastructure;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.servlet.HandlerExceptionResolver;
import sn.groupia.shared.api.ApiException;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(AccessSettings.class)
class AccessConfiguration {
    @Bean @Order(1)
    SecurityFilterChain customerAccessSecurity(HttpSecurity http, AccessSettings settings,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
        var csrf = new CookieCsrfTokenRepository();
        csrf.setCookieName(settings.secureCookie() ? "__Host-groupia_csrf" : "groupia_csrf");
        csrf.setCookieCustomizer(cookie -> cookie.path("/").secure(settings.secureCookie()).httpOnly(true).sameSite("Lax"));
        return http.securityMatcher("/api/public/customer-access/**")
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c -> c.disable()).formLogin(c -> c.disable()).httpBasic(c -> c.disable()).logout(c -> c.disable())
            .csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .headers(h -> h.referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)))
            .authorizeHttpRequests(a -> a.anyRequest().permitAll())
            .exceptionHandling(e -> e.accessDeniedHandler((request,response,failure) ->
                resolver.resolveException(request,response,null,new ApiException(403,"CUSTOMER_CSRF_REQUIRED","Actualisez la page avant de réessayer."))))
            .build();
    }
}
