package sn.groupia.identity.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import sn.groupia.identity.application.service.AccessTokens;
import sn.groupia.identity.application.service.AuthenticationService;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final AuthenticationService authentication;
    private final sn.groupia.identity.infrastructure.hardening.AuthRateLimits limits;

    AuthController(
            AuthenticationService authentication,
            sn.groupia.identity.infrastructure.hardening.AuthRateLimits limits) {
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
    ResponseEntity<AccessTokens.AccessToken> login(@Valid @RequestBody LoginRequest request) {
        limits.subject("login", request.phoneNumber());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(authentication.login(request.phoneNumber(), request.password()));
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
