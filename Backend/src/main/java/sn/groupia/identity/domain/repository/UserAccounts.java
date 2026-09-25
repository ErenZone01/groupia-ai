package sn.groupia.identity.domain.repository;

import java.util.Optional;
import java.util.UUID;
import sn.groupia.identity.domain.model.UserAccount;

public interface UserAccounts {
    UserAccount add(UserAccount account);
    Optional<UserAccount> findByPhoneNumber(String phoneNumber);
    Optional<UserAccount> findById(UUID id);
}
