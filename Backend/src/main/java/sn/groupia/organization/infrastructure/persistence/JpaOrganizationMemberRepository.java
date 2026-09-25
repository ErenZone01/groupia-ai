package sn.groupia.organization.infrastructure.persistence;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.groupia.organization.domain.model.OrganizationMember;

interface JpaOrganizationMemberRepository extends JpaRepository<OrganizationMember, UUID> {
    Optional<OrganizationMember> findByOrganizationIdAndUserAccountId(UUID organizationId, UUID userAccountId);
}
