package sn.groupia.ordering.api;
import java.util.UUID;
import sn.groupia.ordering.api.dto.SupplierProjection;
/** Capture under an existing write transaction and an already-held CLOSED campaign lock.
 * Locks the campaign's customer orders until commit, including pending/expired orders. */
public interface SupplierOrdering { SupplierProjection capture(UUID organizationId, UUID campaignId); }
