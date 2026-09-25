package sn.groupia.organization.domain.repository;
import java.util.*;
import sn.groupia.organization.domain.model.*;
public interface Organizations {
    Organization add(Organization organization);
    Optional<Organization> findActiveById(UUID id);
    List<Organization> findAccessibleByUser(UUID userId, Set<OrganizationRole> roles, int page, int size);
}
