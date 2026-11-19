package sn.groupia.identity.application.service;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.identity.api.CurrentUser;
import sn.groupia.identity.domain.model.UserAccountStatus;
import sn.groupia.identity.domain.repository.UserAccounts;
import sn.groupia.shared.api.ApiException;
import sn.groupia.shared.api.PlatformCurrentActor;

@Service
public class AuthenticatedUser implements CurrentUser, PlatformCurrentActor {
    private final UserAccounts accounts;
    public AuthenticatedUser(UserAccounts accounts) { this.accounts = accounts; }

    @Override
    @Transactional(readOnly = true)
    public UUID requireActiveUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            throw unauthorized();
        }
        UUID id;
        try { id = UUID.fromString(token.getToken().getSubject()); }
        catch (IllegalArgumentException | NullPointerException ex) { throw unauthorized(); }
        if (accounts.findById(id).filter(account -> account.getStatus() == UserAccountStatus.ACTIVE).isEmpty()) {
            throw unauthorized();
        }
        return id;
    }

    private ApiException unauthorized() {
        return new ApiException(401, "AUTHENTICATION_REQUIRED", "Une authentification valide est requise.");
    }
}
