package sn.groupia.identity.application.service;

import java.time.Clock;
import java.time.LocalDate;
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
    private final RefreshSessions sessions;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String dummyHash;

    public AuthenticationService(
            UserAccounts accounts,
            PhoneNumberNormalizer phones,
            PasswordPolicy passwords,
            PasswordEncoder encoder,
            RefreshSessions sessions,
            ApplicationEventPublisher events,
            Clock clock) {
        this.accounts = accounts;
        this.phones = phones;
        this.passwords = passwords;
        this.encoder = encoder;
        this.sessions = sessions;
        this.events = events;
        this.clock = clock;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public UserAccountView register(String phoneNumber, String password, String email) {
        return register(phoneNumber, password, email, null, null, null);
    }

    @Transactional
    public UserAccountView register(String phoneNumber, String password, String email,
            String firstName, String lastName, LocalDate birthDate) {
        String phone = phones.normalize(phoneNumber);
        passwords.validateRegistration(password);
        // Existing internal test fixtures may have a legacy null profile. The public
        // register controller always validates the three fields before entering here.
        if (firstName != null || lastName != null || birthDate != null) {
            if (firstName == null || lastName == null || birthDate == null
                    || birthDate.isBefore(LocalDate.of(1900, 1, 1))
                    || !birthDate.isBefore(LocalDate.now(clock))) {
                throw new sn.groupia.shared.api.ApiException(
                        400, "INVALID_PROFILE", "Vérifiez vos informations personnelles.");
            }
            firstName = cleanName(firstName);
            lastName = cleanName(lastName);
        }

        var account =
                accounts.add(
                        UserAccount.register(
                                phone,
                                email == null ? null : email.strip(),
                                encoder.encode(password),
                                clock.instant(), firstName, lastName, birthDate));
        events.publishEvent(
                new UserAccountCreated(UUID.randomUUID(), clock.instant(), account.getId()));
        return new UserAccountView(
                account.getId(),
                account.getPhoneNumber(),
                account.getEmail(),
                account.getStatus().name(),
                account.getCreatedAt());
    }

    private String cleanName(String value) {
        String name = value.strip();
        if (name.isEmpty() || name.length() > 80
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new sn.groupia.shared.api.ApiException(
                    400, "INVALID_PROFILE", "Vérifiez vos informations personnelles.");
        }
        return name;
    }

    @Transactional
    public RefreshSessions.Issued login(
            String phoneNumber, String password, String previousRefresh) {
        passwords.validateInput(password);
        String phone;
        try {
            phone = phones.normalize(phoneNumber);
        } catch (ApiException ex) {
            throw invalidCredentials();
        }
        var account = accounts.findByPhoneNumber(phone).orElse(null);
        boolean matches =
                encoder.matches(password, account == null ? dummyHash : account.getPasswordHash());
        if (account == null || !matches || account.getStatus() != UserAccountStatus.ACTIVE) {
            throw invalidCredentials();
        }
        return sessions.create(account.getId(), previousRefresh);
    }

    private ApiException invalidCredentials() {
        return new ApiException(401, "INVALID_CREDENTIALS", "Les identifiants sont invalides.");
    }
}
