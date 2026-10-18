package sn.groupia.identity.infrastructure.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import sn.groupia.identity.infrastructure.security.SessionSettings;
import sn.groupia.shared.api.ApiException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class AuthOriginFilter extends OncePerRequestFilter {
    private final SessionSettings settings;
    private final HandlerExceptionResolver resolver;

    AuthOriginFilter(
            SessionSettings settings,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.settings = settings;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path =
                new org.springframework.web.util.UrlPathHelper().getPathWithinApplication(request);
        if (Set.of("/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout")
                .contains(path)) {
            response.setHeader("Cache-Control", "no-store");
            if (!"POST".equals(request.getMethod())) {
                reject(request, response, 405, "AUTH_METHOD_REQUIRED");
                return;
            }
            boolean json = false;
            try {
                var type = MediaType.parseMediaType(Objects.toString(request.getContentType(), ""));
                json = type.getType().equals("application") && type.getSubtype().equals("json");
            } catch (IllegalArgumentException ignored) {
            }
            if (!json) {
                reject(request, response, 415, "AUTH_JSON_REQUIRED");
                return;
            }
            if (!single(request, "Origin")
                    || !settings.allowedOrigins().contains(request.getHeader("Origin"))
                    || !single(request, "X-Groupia-Auth")
                    || !"1".equals(request.getHeader("X-Groupia-Auth"))
                    || Collections.list(request.getHeaders("Sec-Fetch-Site")).stream()
                            .anyMatch("cross-site"::equalsIgnoreCase)) {
                reject(request, response, 403, "AUTH_ORIGIN_REJECTED");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean single(HttpServletRequest r, String name) {
        return Collections.list(r.getHeaders(name)).size() == 1;
    }

    private void reject(HttpServletRequest r, HttpServletResponse s, int status, String code) {
        resolver.resolveException(
                r,
                s,
                null,
                new ApiException(
                        status, code, "La requête d’authentification n’est pas autorisée."));
    }
}
