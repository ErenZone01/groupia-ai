package sn.groupia.media.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import sn.groupia.media.api.dto.PublicProductImage;

/**
 * Public-safe media projection for already-authorized commerce contexts. Only READY normalized
 * renditions are exposed; storage keys and internal media ids never leave Media.
 */
public interface PublicProductMediaQueries {
    Map<UUID, List<PublicProductImage>> readyImages(UUID organizationId, Set<UUID> productIds);
}
