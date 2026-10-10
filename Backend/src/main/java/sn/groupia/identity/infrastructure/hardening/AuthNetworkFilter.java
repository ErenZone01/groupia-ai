package sn.groupia.identity.infrastructure.hardening;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import sn.groupia.shared.api.ApiException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
class AuthNetworkFilter extends OncePerRequestFilter {
    private final AuthRateLimits limits;
    private final HandlerExceptionResolver resolver;

    AuthNetworkFilter(
            AuthRateLimits limits,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.limits = limits;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path =
                new org.springframework.web.util.UrlPathHelper().getPathWithinApplication(request);
        if (request.getMethod().equals("POST")
                && (path.equals("/api/auth/login") || path.equals("/api/auth/register"))) {
            try {
                limits.network(
                        path.endsWith("/login") ? "login" : "register", request.getRemoteAddr());
            } catch (ApiException ex) {
                resolver.resolveException(request, response, null, ex);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
