package sn.groupia.identity.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import sn.groupia.identity.application.service.AccessTokens;
import sn.groupia.identity.application.service.AuthenticationService;
import sn.groupia.identity.application.service.RefreshSessions;
import sn.groupia.identity.infrastructure.security.SessionSettings;
import sn.groupia.shared.api.ApiException;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final AuthenticationService authentication;
    private final sn.groupia.identity.infrastructure.hardening.AuthRateLimits limits;

    private final RefreshSessions sessions;
    private final SessionSettings settings;
    private final Clock clock;

    AuthController(
            AuthenticationService authentication,
            sn.groupia.identity.infrastructure.hardening.AuthRateLimits limits,
            RefreshSessions sessions,
            SessionSettings settings,
            Clock clock) {
        this.sessions = sessions;
        this.settings = settings;
        this.clock = clock;
        this.authentication = authentication;
        this.limits = limits;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
        limits.subject("register", request.phoneNumber());
        try {
            authentication.register(request.phoneNumber(), request.password(), request.email());
        } catch (sn.groupia.shared.api.ApiException ex) {
            if (!ex.code().equals("PHONE_ALREADY_REGISTERED")) throw ex;
        }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/login")
    ResponseEntity<AccessTokens.AccessToken> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        limits.subject("login", request.phoneNumber());
        return issued(
                authentication.login(request.phoneNumber(), request.password(), cookie(http)));
    }

    @io.swagger.v3.oas.annotations.Operation(
            description =
                    "Cookie HttpOnly uniquement. Corps vide, Origin exacte autorisée, X-Groupia-Auth: 1 et application/json requis. Rotation commitée avant réponse ; 409 concurrence, 401 invalide/replay, 429 quota avec Retry-After.")
    @PostMapping(value = "/refresh", consumes = "application/json")
    ResponseEntity<AccessTokens.AccessToken> refresh(HttpServletRequest request) {
        cookieOnly(request);
        var result = sessions.refresh(cookie(request));
        // Transaction interceptor has committed, including replay revocation, before HTTP errors.
        if (result.error() != null)
            throw new ApiException(
                    result.status(),
                    result.error(),
                    "La session ne peut pas être renouvelée.",
                    result.retryAfterSeconds() > 0
                            ? Map.of("retryAfterSeconds", result.retryAfterSeconds())
                            : Map.of());
        return issued(result.issued());
    }

    @io.swagger.v3.oas.annotations.Operation(
            description =
                    "Révoque la famille du cookie courant ou historique. Corps vide, Origin exacte autorisée, X-Groupia-Auth: 1 et application/json requis. Idempotent, sans Bearer.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping(value = "/logout", consumes = "application/json")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        cookieOnly(request);
        sessions.logout(cookie(request));
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookieHeader("", 0))
                .build();
    }

    private void cookieOnly(HttpServletRequest request) {
        if (request.getQueryString() != null
                || request.getHeader(HttpHeaders.AUTHORIZATION) != null
                || request.getContentLengthLong() > 0
                || request.getHeader("Transfer-Encoding") != null)
            throw new ApiException(
                    400, "AUTH_COOKIE_ONLY", "Utilisez uniquement le cookie de session.");
    }

    private String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        var matches =
                Arrays.stream(request.getCookies())
                        .filter(c -> c.getName().equals(settings.cookieName()))
                        .toList();
        if (matches.size() > 1)
            throw new ApiException(401, "AUTH_REFRESH_INVALID", "La session est invalide.");
        return matches.isEmpty() ? null : matches.getFirst().getValue();
    }

    private ResponseEntity<AccessTokens.AccessToken> issued(RefreshSessions.Issued issued) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookieHeader(
                                issued.refreshToken(),
                                Math.max(
                                        0,
                                        Duration.between(clock.instant(), issued.cookieExpiresAt())
                                                .toSeconds())))
                .body(issued.access());
    }

    private String cookieHeader(String value, long age) {
        return ResponseCookie.from(settings.cookieName(), value)
                .httpOnly(true)
                .secure(settings.cookieSecure())
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(age)
                .build()
                .toString();
    }

    record RegisterRequest(
            @NotBlank @Size(max = 40) String phoneNumber,
            @NotBlank @Size(max = 72) String password,
            @Email
                    @Size(max = 254)
                    @Pattern(regexp = ".*\\S.*", message = "doit être renseigné ou absent")
                    String email) {
        @Override
        public String toString() {
            return "RegisterRequest[REDACTED]";
        }
    }

    record LoginRequest(
            @NotBlank @Size(max = 40) String phoneNumber,
            @NotBlank @Size(max = 72) String password) {
        @Override
        public String toString() {
            return "LoginRequest[REDACTED]";
        }
    }
}
