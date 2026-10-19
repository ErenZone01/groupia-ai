package sn.groupia.catalog.infrastructure.persistence;

import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import sn.groupia.catalog.api.CatalogProducts;
import sn.groupia.shared.api.ApiException;

@Component
class CatalogProductAccess implements CatalogProducts {
    private final NamedParameterJdbcTemplate jdbc;

    CatalogProductAccess(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void requireProducts(UUID organizationId, Set<UUID> productIds) {
        if (productIds.isEmpty()) return;
        var count =
                jdbc.queryForObject(
                        "select count(*) from products where organization_id=:org and id in (:ids)",
                        Map.of("org", organizationId, "ids", productIds),
                        Integer.class);
        if (count == null || count != productIds.size()) throw missing();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireProductForMutation(
            UUID organizationId, UUID productId, boolean requireActive) {
        var statuses =
                jdbc.queryForList(
                        "select status from products where organization_id=:org and id=:id for share",
                        Map.of("org", organizationId, "id", productId),
                        String.class);
        if (statuses.isEmpty()) throw missing();
        if (requireActive && !statuses.getFirst().equals("ACTIVE"))
            throw new ApiException(409, "PRODUCT_ARCHIVED", "Ce produit est archivé.");
    }

    private ApiException missing() {
        return new ApiException(
                404, "PRODUCT_NOT_FOUND", "Produit introuvable dans cette boutique.");
    }
}
