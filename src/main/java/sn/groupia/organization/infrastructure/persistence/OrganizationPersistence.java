package sn.groupia.organization.infrastructure.persistence;

import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import sn.groupia.organization.domain.model.*;
import sn.groupia.organization.domain.repository.*;

@Repository
class OrganizationPersistence implements Organizations, OrganizationMembers {
    private final JpaOrganizationRepository organizations;
    private final JpaOrganizationMemberRepository members;

    OrganizationPersistence(JpaOrganizationRepository organizations, JpaOrganizationMemberRepository members) {
        this.organizations = organizations;
        this.members = members;
    }

    @Override public Organization add(Organization organization) { return organizations.saveAndFlush(organization); }
    @Override public OrganizationMember add(OrganizationMember member) { return members.saveAndFlush(member); }
    @Override public Optional<Organization> findActiveById(UUID id) {
        return organizations.findByIdAndStatus(id, OrganizationStatus.ACTIVE);
    }
    @Override public Optional<OrganizationMember> findByOrganizationAndUser(UUID orgId, UUID userId) {
        return members.findByOrganizationIdAndUserAccountId(orgId, userId);
    }
    @Override public List<Organization> findAccessibleByUser(UUID userId, Set<OrganizationRole> roles,
            int page, int size) {
        return organizations.findAccessible(userId, MembershipStatus.ACTIVE, roles,
                OrganizationStatus.ACTIVE, PageRequest.of(page, size));
    }
}
