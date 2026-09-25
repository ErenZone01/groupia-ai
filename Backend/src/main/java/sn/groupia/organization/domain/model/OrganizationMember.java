package sn.groupia.organization.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "organization_members")
public class OrganizationMember {
    @Id private UUID id;
    @Column(nullable = false) private UUID organizationId;
    @Column(nullable = false) private UUID userAccountId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16) private OrganizationRole role;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16) private MembershipStatus status;
    @Column(nullable = false) private Instant joinedAt;
    private Instant revokedAt;
    @Version private Long version;

    protected OrganizationMember() {}

    public static OrganizationMember initialOwner(UUID organizationId, UUID userAccountId, Instant now) {
        var member = new OrganizationMember();
        member.id = UUID.randomUUID();
        member.organizationId = Objects.requireNonNull(organizationId);
        member.userAccountId = Objects.requireNonNull(userAccountId);
        member.role = OrganizationRole.OWNER;
        member.status = MembershipStatus.ACTIVE;
        member.joinedAt = Objects.requireNonNull(now);
        return member;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getUserAccountId() { return userAccountId; }
    public OrganizationRole getRole() { return role; }
    public MembershipStatus getStatus() { return status; }
    public Instant getJoinedAt() { return joinedAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
