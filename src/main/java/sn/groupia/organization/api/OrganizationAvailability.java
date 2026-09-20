package sn.groupia.organization.api;
import java.util.UUID;
/** Minimal module-only lookup for already resolved public organization contexts. */
public interface OrganizationAvailability { boolean isActive(UUID organizationId); String countryCode(UUID organizationId); }
