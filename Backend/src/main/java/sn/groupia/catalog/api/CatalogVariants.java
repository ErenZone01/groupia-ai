package sn.groupia.catalog.api;
import java.util.*;
import sn.groupia.catalog.api.dto.CatalogVariantView;
/** Internal module API, not an anonymous HTTP catalogue. Callers authorize the owning context. */
public interface CatalogVariants {
    Map<UUID, sn.groupia.catalog.api.dto.CatalogCommercialSnapshot> commercialSnapshots(UUID organizationId, Set<UUID> variantIds);
    Map<UUID, CatalogVariantView> findByIds(UUID organizationId, Set<UUID> variantIds);
}
