package sn.groupia.identity.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.groupia.identity.domain.model.UserAccount;

interface JpaUserAccountRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByPhoneNumber(String phoneNumber);
}
