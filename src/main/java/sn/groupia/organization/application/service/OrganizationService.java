package sn.groupia.organization.application.service;

import java.time.Clock;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.identity.api.CurrentUser;
import sn.groupia.organization.api.OrganizationPermission;
import sn.groupia.organization.api.dto.OrganizationView;
import sn.groupia.organization.api.event.OrganizationCreated;
import sn.groupia.organization.domain.model.*;
import sn.groupia.organization.domain.repository.*;
import sn.groupia.shared.api.ApiException;

@Service
public class OrganizationService {
    private final Organizations organizations;
    private final OrganizationMembers members;
    private final CurrentUser currentUser;
    private final OrganizationAuthorization authorization;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public OrganizationService(Organizations organizations, OrganizationMembers members, CurrentUser currentUser,
            OrganizationAuthorization authorization, Clock clock, ApplicationEventPublisher events) {
        this.organizations = organizations;
        this.members = members;
        this.currentUser = currentUser;
        this.authorization = authorization;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public OrganizationView create(String name) {
        UUID userId = currentUser.requireActiveUserId();
        var now = clock.instant();
        var organization = organizations.add(Organization.create(name, now));
        members.add(OrganizationMember.initialOwner(organization.getId(), userId, now));
        events.publishEvent(new OrganizationCreated(UUID.randomUUID(), now, organization.getId(), userId));
        return view(organization);
    }

    @Transactional(readOnly = true)
    public List<OrganizationView> list(int page, int size) {
        UUID userId = currentUser.requireActiveUserId();
        return organizations.findAccessibleByUser(userId,
                authorization.rolesGranting(OrganizationPermission.VIEW_ORGANIZATION), page, size)
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public OrganizationView get(UUID organizationId) {
        authorization.requirePermission(organizationId, OrganizationPermission.VIEW_ORGANIZATION);
        return organizations.findActiveById(organizationId).map(this::view)
                .orElseThrow(() -> new ApiException(404, "ORGANIZATION_NOT_FOUND", "L'organisation est introuvable."));
    }

    private OrganizationView view(Organization organization) {
        return new OrganizationView(organization.getId(), organization.getName(), organization.getStatus().name(),
                organization.getDefaultCurrency(), organization.getCountryCode(), organization.getTimezone(),
                organization.getCreatedAt(), organization.getUpdatedAt());
    }
}
