package sn.groupia.identity.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import sn.groupia.identity.domain.model.UserAccount;
import sn.groupia.identity.domain.repository.UserAccounts;
import sn.groupia.shared.api.ApiException;

@Repository
class UserAccountPersistence implements UserAccounts {
    private final JpaUserAccountRepository repository;

    UserAccountPersistence(JpaUserAccountRepository repository) { this.repository = repository; }

    @Override
    public UserAccount add(UserAccount account) {
        try {
            return repository.saveAndFlush(account);
        } catch (DataIntegrityViolationException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException constraint
                        && "uk_user_accounts_phone".equals(constraint.getConstraintName())) {
                    throw new ApiException(409, "PHONE_ALREADY_REGISTERED", "Ce numéro est déjà inscrit.");
                }
            }
            throw ex;
        }
    }

    @Override public Optional<UserAccount> findByPhoneNumber(String phone) {
        return repository.findByPhoneNumber(phone);
    }
    @Override public Optional<UserAccount> findById(UUID id) { return repository.findById(id); }
}
