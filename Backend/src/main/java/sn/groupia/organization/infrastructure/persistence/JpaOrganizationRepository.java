package sn.groupia.organization.infrastructure.persistence;

import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import sn.groupia.organization.domain.model.*;

interface JpaOrganizationRepository extends JpaRepository<Organization, UUID> {
    Optional<Organization> findByIdAndStatus(UUID id, OrganizationStatus status);

    @Query("""
            select o from Organization o join OrganizationMember m on m.organizationId = o.id
            where m.userAccountId = :userId and m.status = :membershipStatus
              and m.role in :roles and o.status = :organizationStatus
            order by o.createdAt desc, o.id
            """)
    List<Organization> findAccessible(UUID userId, MembershipStatus membershipStatus,
            Set<OrganizationRole> roles, OrganizationStatus organizationStatus, Pageable pageable);
}
