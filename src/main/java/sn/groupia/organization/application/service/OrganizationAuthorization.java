package sn.groupia.organization.application.service;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.identity.api.CurrentUser;
import sn.groupia.organization.api.*;
import sn.groupia.organization.domain.model.*;
import sn.groupia.organization.domain.repository.OrganizationMembers;
import sn.groupia.organization.domain.repository.Organizations;
import sn.groupia.shared.api.ApiException;

@Service
public class OrganizationAuthorization implements OrganizationAccess {
    private static final Map<OrganizationRole, Set<OrganizationPermission>> PERMISSIONS = Map.of(
            OrganizationRole.OWNER, Set.of(OrganizationPermission.values()),
            OrganizationRole.MANAGER, Set.of(OrganizationPermission.VIEW_ORGANIZATION, OrganizationPermission.VIEW_CATALOG, OrganizationPermission.MANAGE_CATALOG),
            OrganizationRole.OPERATOR, Set.of(OrganizationPermission.VIEW_ORGANIZATION, OrganizationPermission.VIEW_CATALOG),
            OrganizationRole.VIEWER, Set.of(OrganizationPermission.VIEW_ORGANIZATION, OrganizationPermission.VIEW_CATALOG));

    private final CurrentUser currentUser;
    private final OrganizationMembers members;
    private final Organizations organizations;

    public OrganizationAuthorization(CurrentUser currentUser, OrganizationMembers members, Organizations organizations) {
        this.currentUser = currentUser;
        this.members = members;
        this.organizations = organizations;
    }

    @Override
    @Transactional(readOnly = true)
    public void requirePermission(UUID organizationId, OrganizationPermission permission) {
        UUID userId = currentUser.requireActiveUserId();
        var member = members.findByOrganizationAndUser(organizationId, userId);
        if (member.isEmpty() || member.get().getStatus() != MembershipStatus.ACTIVE
                || !rolesGranting(permission).contains(member.get().getRole())) {
            throw new ApiException(403, "ORGANIZATION_ACCESS_DENIED", "L'accès à cette organisation est interdit.");
        }
        requireActiveCatalogOrganization(organizationId, permission);
    }

    // Existing organization reads retain their historical policy; catalogue operations require an active organization.
    private void requireActiveCatalogOrganization(UUID organizationId, OrganizationPermission permission) {
        if ((permission == OrganizationPermission.VIEW_CATALOG || permission == OrganizationPermission.MANAGE_CATALOG)
                && organizations.findActiveById(organizationId).isEmpty()) {
            throw new ApiException(404, "ORGANIZATION_NOT_FOUND", "Organisation indisponible.");
        }
    }

    public Set<OrganizationRole> rolesGranting(OrganizationPermission permission) {
        Objects.requireNonNull(permission);
        return PERMISSIONS.entrySet().stream().filter(entry -> entry.getValue().contains(permission))
                .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }
}

