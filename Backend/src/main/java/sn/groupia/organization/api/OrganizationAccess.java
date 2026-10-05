package sn.groupia.organization.api;
import java.util.UUID;
public interface OrganizationAccess {
    boolean hasPermission(UUID organizationId, OrganizationPermission permission);
    void requirePermission(UUID organizationId, OrganizationPermission permission);
    UUID requireActorPermission(UUID organizationId, OrganizationPermission permission);
}
