package sn.groupia.organization.domain.repository;
import java.util.*;
import sn.groupia.organization.domain.model.OrganizationMember;
public interface OrganizationMembers {
    OrganizationMember add(OrganizationMember member);
    Optional<OrganizationMember> findByOrganizationAndUser(UUID organizationId, UUID userAccountId);
}
