package sn.groupia.organization.application.service;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.organization.api.OrganizationAvailability;
import sn.groupia.organization.api.OrganizationSubscriptionContext;
import sn.groupia.organization.api.dto.OrganizationSubscriptionFacts;
import sn.groupia.organization.domain.repository.Organizations;

@Service
@Transactional(readOnly = true)
class OrganizationAvailabilityQueries implements OrganizationAvailability, OrganizationSubscriptionContext {
    private final Organizations organizations;
    OrganizationAvailabilityQueries(Organizations organizations) { this.organizations = organizations; }
    @Override public String countryCode(UUID organizationId) {
        return organizations.findActiveById(organizationId).map(o -> o.getCountryCode())
                .orElseThrow(() -> new sn.groupia.shared.api.ApiException(409, "GROUPING_NOT_ORDERABLE", "Organisation indisponible."));
    }
    @Override public boolean isActive(UUID organizationId) { return organizations.findActiveById(organizationId).isPresent(); }
    @Override public OrganizationSubscriptionFacts subscriptionFacts(UUID organizationId) {
        return organizations.findActiveById(organizationId)
                .map(o -> new OrganizationSubscriptionFacts(o.getId(), o.getName(), o.getTimezone(), o.getCreatedAt()))
                .orElseThrow(() -> new sn.groupia.shared.api.ApiException(404, "ORGANIZATION_NOT_FOUND", "Organisation introuvable."));
    }
}
