package sn.groupia.identity.application.service;
import java.util.UUID;
public interface AccessTokens {
    AccessToken issue(UUID userAccountId);

    record AccessToken(String accessToken, String tokenType, long expiresIn) {
        @Override public String toString() { return "AccessToken[REDACTED]"; }
    }
}
