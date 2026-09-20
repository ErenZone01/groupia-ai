package sn.groupia.identity.application.service;

import java.time.Clock;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.identity.api.dto.UserAccountView;
import sn.groupia.identity.api.event.UserAccountCreated;
import sn.groupia.identity.domain.model.*;
import sn.groupia.identity.domain.repository.UserAccounts;
import sn.groupia.shared.api.ApiException;

@Service
public class AuthenticationService {
    private final UserAccounts accounts;
    private final PhoneNumberNormalizer phones;
    private final PasswordPolicy passwords;
    private final PasswordEncoder encoder;
    private final AccessTokens tokens;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String dummyHash;

    public AuthenticationService(UserAccounts accounts, PhoneNumberNormalizer phones, PasswordPolicy passwords,
            PasswordEncoder encoder, AccessTokens tokens, ApplicationEventPublisher events, Clock clock) {
        this.accounts = accounts;
        this.phones = phones;
        this.passwords = passwords;
        this.encoder = encoder;
        this.tokens = tokens;
        this.events = events;
        this.clock = clock;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public UserAccountView register(String phoneNumber, String password, String email) {
        String phone = phones.normalize(phoneNumber);
        passwords.validateRegistration(password);
        var account = accounts.add(UserAccount.register(phone, email == null ? null : email.strip(),
                encoder.encode(password), clock.instant()));
        events.publishEvent(new UserAccountCreated(UUID.randomUUID(), clock.instant(), account.getId()));
        return new UserAccountView(account.getId(), account.getPhoneNumber(), account.getEmail(),
                account.getStatus().name(), account.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public AccessTokens.AccessToken login(String phoneNumber, String password) {
        passwords.validateInput(password);
        String phone;
        try { phone = phones.normalize(phoneNumber); }
        catch (ApiException ex) { throw invalidCredentials(); }
        var account = accounts.findByPhoneNumber(phone).orElse(null);
        boolean matches = encoder.matches(password, account == null ? dummyHash : account.getPasswordHash());
        if (account == null || !matches || account.getStatus() != UserAccountStatus.ACTIVE) {
            throw invalidCredentials();
        }
        return tokens.issue(account.getId());
    }

    private ApiException invalidCredentials() {
        return new ApiException(401, "INVALID_CREDENTIALS", "Les identifiants sont invalides.");
    }
}
