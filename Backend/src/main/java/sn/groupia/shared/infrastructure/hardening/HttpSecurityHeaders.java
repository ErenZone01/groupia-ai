package sn.groupia.shared.infrastructure.hardening;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies to both bearer and cookie/CSRF chains, including error responses. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class HttpSecurityHeaders extends OncePerRequestFilter {
    private final boolean production;

    HttpSecurityHeaders(@Value("${groupia.security.production:false}") boolean production) {
        this.production = production;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader(
                "Permissions-Policy",
                "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
        if (production) {
            response.setHeader(
                    "Content-Security-Policy",
                    "default-src 'none'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'");
            if (request.isSecure())
                response.setHeader("Strict-Transport-Security", "max-age=31536000");
        }
        chain.doFilter(request, response);
    }
}
