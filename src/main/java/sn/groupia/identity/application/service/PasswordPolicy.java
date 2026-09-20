package sn.groupia.identity.application.service;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import sn.groupia.shared.api.ApiException;

@Component
public class PasswordPolicy {
    public void validateRegistration(String password) {
        validateInput(password);
        if (password.codePointCount(0, password.length()) < 15) {
            throw invalidPassword();
        }
    }

    public void validateInput(String password) {
        if (password == null || password.isBlank() || password.indexOf('\0') >= 0
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalidPassword();
        }
    }

    private ApiException invalidPassword() {
        return new ApiException(400, "INVALID_PASSWORD",
                "Le mot de passe doit contenir au moins 15 caractères et au plus 72 octets UTF-8.");
    }
}
