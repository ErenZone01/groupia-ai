package sn.groupia.catalog.api;

import java.util.Set;
import java.util.UUID;

/** Tenant-bound product checks. Callers authorize their operation before calling this API. */
public interface CatalogProducts {
    void requireProducts(UUID organizationId, Set<UUID> productIds);

    /** Joins the caller's transaction and locks the product until commit, before gallery locks. */
    void requireProductForMutation(UUID organizationId, UUID productId, boolean requireActive);
}
