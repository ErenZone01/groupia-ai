package sn.groupia.organization.api;
import java.util.UUID;
public interface OrganizationAccess {
    void requirePermission(UUID organizationId, OrganizationPermission permission);
}
