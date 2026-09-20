package sn.groupia.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "user_accounts")
public class UserAccount {
    @Id private UUID id;
    @Column(nullable = false, length = 16) private String phoneNumber;
    @Column(length = 254) private String email;
    @JsonIgnore
    @Column(nullable = false, length = 60) private String passwordHash;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16) private UserAccountStatus status;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    @Version private Long version;

    protected UserAccount() {}

    public static UserAccount register(String phoneNumber, String email, String passwordHash, Instant now) {
        var account = new UserAccount();
        account.id = UUID.randomUUID();
        account.phoneNumber = Objects.requireNonNull(phoneNumber);
        account.email = email;
        account.passwordHash = Objects.requireNonNull(passwordHash);
        account.status = UserAccountStatus.ACTIVE;
        account.createdAt = account.updatedAt = Objects.requireNonNull(now);
        return account;
    }

    public UUID getId() { return id; }
    public String getPhoneNumber() { return phoneNumber; }
    public String getEmail() { return email; }
    @JsonIgnore public String getPasswordHash() { return passwordHash; }
    public UserAccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
