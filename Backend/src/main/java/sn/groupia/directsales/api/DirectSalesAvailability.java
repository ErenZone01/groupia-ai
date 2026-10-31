package sn.groupia.directsales.api;

import java.util.UUID;

/** Tenant-scoped direct storefront state without leaking public URL or private prices. */
public interface DirectSalesAvailability {
    boolean hasPublishedOffers(UUID organizationId);
}
