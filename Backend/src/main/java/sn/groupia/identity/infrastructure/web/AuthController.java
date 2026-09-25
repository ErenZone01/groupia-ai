package sn.groupia.identity.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import sn.groupia.identity.api.dto.UserAccountView;
import sn.groupia.identity.application.service.AccessTokens;
import sn.groupia.identity.application.service.AuthenticationService;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final AuthenticationService authentication;
    AuthController(AuthenticationService authentication) { this.authentication = authentication; }

    @PostMapping("/register")
    ResponseEntity<UserAccountView> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(authentication.register(request.phoneNumber(), request.password(), request.email()));
    }

    @PostMapping("/login")
    ResponseEntity<AccessTokens.AccessToken> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(authentication.login(request.phoneNumber(), request.password()));
    }

    record RegisterRequest(@NotBlank @Size(max = 40) String phoneNumber,
            @NotBlank @Size(max = 72) String password,
            @Email @Size(max = 254) @Pattern(regexp = ".*\\S.*", message = "doit être renseigné ou absent") String email) {
        @Override public String toString() { return "RegisterRequest[REDACTED]"; }
    }

    record LoginRequest(@NotBlank @Size(max = 40) String phoneNumber,
            @NotBlank @Size(max = 72) String password) {
        @Override public String toString() { return "LoginRequest[REDACTED]"; }
    }
}
