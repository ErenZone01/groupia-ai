package sn.groupia.directsales.application;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.catalog.api.CatalogVariants;
import sn.groupia.directsales.api.DirectSalesAvailability;
import sn.groupia.media.api.PublicProductMediaQueries;
import sn.groupia.organization.api.OrganizationAccess;
import sn.groupia.organization.api.OrganizationAvailability;
import sn.groupia.organization.api.OrganizationPermission;
import sn.groupia.shared.api.ApiException;
import sn.groupia.subscription.api.SubscriptionEntitlements;

/** Direct sales are independent of groupings: no pseudo-campaign or implicit checkout. */
@Service
@Transactional(readOnly = true)
public class DirectStorefrontService implements DirectSalesAvailability {
    private final JdbcTemplate jdbc;
    private final CatalogVariants catalog;
    private final PublicProductMediaQueries media;
    private final OrganizationAccess access;
    private final OrganizationAvailability organizations;
    private final SubscriptionEntitlements entitlements;
    private final Clock clock;

    public DirectStorefrontService(
            JdbcTemplate jdbc,
            CatalogVariants catalog,
            PublicProductMediaQueries media,
            OrganizationAccess access,
            OrganizationAvailability organizations,
            SubscriptionEntitlements entitlements,
            Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.media = media;
        this.access = access;
        this.organizations = organizations;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    public record Offer(
            UUID id,
            UUID variantId,
            long priceXof,
            boolean published,
            long version,
            String productName,
            String description,
            Map<String, String> attributes,
            String saleUnit,
            boolean usable,
            String imageUrl) {}

    public record Storefront(
            UUID publicIdentifier,
            String displayName,
            String currency,
            List<Offer> offers,
            String pickupAddress,
            String pickupInstructions,
            long pickupVersion) {}

    private record Store(
            UUID id, UUID organizationId, UUID publicIdentifier, String name,
            String pickupAddress, String pickupInstructions, long pickupVersion) {}

    private record OfferRow(UUID id, UUID variantId, long price, boolean published, long version) {}

    private final org.springframework.jdbc.core.RowMapper<Store> storeMapper =
            (rs, rowNum) ->
                    new Store(
                            rs.getObject("id", UUID.class),
                            rs.getObject("organization_id", UUID.class),
                            rs.getObject("public_identifier", UUID.class),
                            rs.getString("display_name"),
                            rs.getString("pickup_address"),
                            rs.getString("pickup_instructions"),
                            rs.getLong("pickup_version"));

    private final org.springframework.jdbc.core.RowMapper<OfferRow> offerMapper =
            (rs, rowNum) ->
                    new OfferRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("product_variant_id", UUID.class),
                            rs.getLong("price_xof"),
                            rs.getBoolean("published"),
                            rs.getLong("version"));

    private List<Store> findByOrganization(UUID organizationId) {
        return jdbc.query(
                "select id, organization_id, public_identifier, display_name,"
                        + " pickup_address,pickup_instructions,pickup_version"
                        + " from direct_storefronts where organization_id = ?",
                storeMapper,
                organizationId);
    }

    @Override
    public boolean hasPublishedOffers(UUID organizationId) {
        var stores = findByOrganization(organizationId);
        return !stores.isEmpty()
                && organizations.isActive(organizationId)
                && !project(stores.getFirst(), true).offers().isEmpty();
    }

    public Storefront manager(UUID organizationId) {
        access.requirePermission(organizationId, OrganizationPermission.VIEW_CATALOG);
        var stores = findByOrganization(organizationId);
        return stores.isEmpty()
                ? new Storefront(null, null, "XOF", List.of(), null, null, 0)
                : project(stores.getFirst(), false);
    }

    @Transactional
    public Storefront setName(UUID organizationId, String rawName) {
        access.requirePermission(organizationId, OrganizationPermission.MANAGE_CATALOG);
        String name = cleanName(rawName);
        Instant now = clock.instant();
        // The unique organization constraint enforces one stable public link per shop.
        jdbc.update(
                "insert into direct_storefronts"
                        + " (id,organization_id,public_identifier,display_name,created_at,updated_at)"
                        + " values (?,?,?,?,?,?)"
                        + " on conflict (organization_id) do update"
                        + " set display_name = excluded.display_name, updated_at = excluded.updated_at",
                UUID.randomUUID(),
                organizationId,
                UUID.randomUUID(),
                name,
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now));
        return manager(organizationId);
    }

    /**
     * Updates the public pickup destination with optimistic concurrency. Previously placed
     * orders keep their own immutable location snapshot, never this current storefront value.
     * Setting an empty address disables pickup for new orders.
     */
    @Transactional
    public Storefront setPickup(
            UUID organizationId, String rawAddress, String rawInstructions, long expectedVersion) {
        access.requirePermission(organizationId, OrganizationPermission.MANAGE_CATALOG);
        if (expectedVersion < 0) {
            throw new ApiException(400, "INVALID_DIRECT_PICKUP", "Version de retrait invalide.");
        }
        var store = requireStore(organizationId, true);
        String address = trimmedOrNull(rawAddress);
        String instructions = trimmedOrNull(rawInstructions);
        if (address != null && (address.length() < 5 || address.length() > 400)) {
            throw new ApiException(400, "INVALID_DIRECT_PICKUP", "Adresse de retrait invalide.");
        }
        if (instructions != null && (address == null || instructions.length() > 500)) {
            throw new ApiException(400, "INVALID_DIRECT_PICKUP", "Instructions de retrait invalides.");
        }
        if (containsControl(address) || containsControl(instructions)) {
            throw new ApiException(400, "INVALID_DIRECT_PICKUP", "Caractères non autorisés.");
        }
        int updated =
                jdbc.update(
                        "update direct_storefronts set pickup_address=?,pickup_instructions=?,"
                                + "pickup_version=pickup_version+1,updated_at=?"
                                + " where organization_id=? and id=? and pickup_version=?",
                        address,
                        instructions,
                        java.sql.Timestamp.from(clock.instant()),
                        organizationId,
                        store.id(),
                        expectedVersion);
        if (updated != 1) {
            throw new ApiException(
                    409, "DIRECT_PICKUP_VERSION_CONFLICT", "Le lieu de retrait a changé. Actualisez.");
        }
        return manager(organizationId);
    }

    private static String trimmedOrNull(String input) {
        return input == null || input.isBlank() ? null : input.strip();
    }

    private static boolean containsControl(String value) {
        return value != null && value.codePoints().anyMatch(Character::isISOControl);
    }

    @Transactional
    public Storefront add(UUID organizationId, UUID variantId, long priceXof) {
        access.requirePermission(organizationId, OrganizationPermission.MANAGE_CATALOG);
        requirePrice(priceXof);
        var store = requireStore(organizationId, true);
        var variant = catalog.findByIds(organizationId, Set.of(variantId)).get(variantId);
        if (variant == null || !variant.usable()) {
            throw new ApiException(
                    409, "DIRECT_PRODUCT_UNAVAILABLE", "Cette variante n'est pas disponible.");
        }
        // Serialize creations for a given store; cap and uniqueness stay correct concurrently.
        var count =
                jdbc.queryForObject(
                        "select count(*) from direct_offers where storefront_id = ?",
                        Integer.class,
                        store.id());
        if (count != null && count >= 100) {
            throw new ApiException(
                    409, "DIRECT_LIMIT_REACHED", "Maximum 100 offres de vente directe.");
        }
        var now = clock.instant();
        try {
            jdbc.update(
                    "insert into direct_offers"
                            + " (id,organization_id,storefront_id,product_variant_id,price_xof,"
                            + " published,version,created_at,updated_at)"
                            + " values (?,?,?,?,?,false,0,?,?)",
                    UUID.randomUUID(),
                    organizationId,
                    store.id(),
                    variantId,
                    priceXof,
                    java.sql.Timestamp.from(now),
                    java.sql.Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "DIRECT_OFFER_EXISTS", "Cette variante est déjà proposée.");
        }
        return manager(organizationId);
    }

    @Transactional
    public Storefront update(
            UUID organizationId, UUID offerId, long version, long priceXof, boolean published) {
        access.requirePermission(organizationId, OrganizationPermission.MANAGE_CATALOG);
        requirePrice(priceXof);
        var store = requireStore(organizationId, true);
        var existing =
                jdbc.query(
                        "select id,product_variant_id,price_xof,published,version"
                                + " from direct_offers where storefront_id = ? and id = ?",
                        offerMapper,
                        store.id(),
                        offerId);
        if (existing.isEmpty()) {
            throw new ApiException(404, "DIRECT_OFFER_NOT_FOUND", "Offre introuvable.");
        }
        var offer = existing.getFirst();
        if (published) {
            entitlements.requireNewBusinessAllowed(organizationId);
            if (!organizations.isActive(organizationId)
                    || !Optional.ofNullable(
                                    catalog.findByIds(organizationId, Set.of(offer.variantId()))
                                            .get(offer.variantId()))
                            .map(sn.groupia.catalog.api.dto.CatalogVariantView::usable)
                            .orElse(false)) {
                throw new ApiException(409, "DIRECT_PRODUCT_UNAVAILABLE", "Produit non publiable.");
            }
        }
        int updated =
                jdbc.update(
                        "update direct_offers set price_xof=?, published=?,version=version+1,"
                                + "updated_at=? where storefront_id=? and id=? and version=?",
                        priceXof,
                        published,
                        java.sql.Timestamp.from(clock.instant()),
                        store.id(),
                        offerId,
                        version);
        if (updated != 1) {
            throw new ApiException(
                    409, "DIRECT_VERSION_CONFLICT", "Offre modifiée. Rechargez la page.");
        }
        return manager(organizationId);
    }

    public Storefront publicStore(UUID identifier) {
        var stores =
                jdbc.query(
                        "select id,organization_id,public_identifier,display_name,"
                                + " pickup_address,pickup_instructions,pickup_version"
                                + " from direct_storefronts where public_identifier = ?",
                        storeMapper,
                        identifier);
        if (stores.isEmpty() || !organizations.isActive(stores.getFirst().organizationId())) {
            throw unavailable();
        }
        var result = project(stores.getFirst(), true);
        if (result.offers().isEmpty()) throw unavailable();
        return result;
    }

    private Storefront project(Store store, boolean publicOnly) {
        var all =
                jdbc.query(
                        "select id,product_variant_id,price_xof,published,version"
                                + " from direct_offers where storefront_id = ?"
                                + (publicOnly ? " and published = true" : "")
                                + " order by created_at desc, id limit 100",
                        offerMapper,
                        store.id());
        var ids = new HashSet<UUID>();
        all.forEach(o -> ids.add(o.variantId()));
        Map<UUID, sn.groupia.catalog.api.dto.CatalogCommercialSnapshot> snapshots =
                ids.isEmpty() ? Map.of() : catalog.commercialSnapshots(store.organizationId(), ids);
        var products = new HashSet<UUID>();
        snapshots.values().forEach(snapshot -> products.add(snapshot.productId()));
        Map<UUID, List<sn.groupia.media.api.dto.PublicProductImage>> images =
                products.isEmpty() ? Map.of() : media.readyImages(store.organizationId(), products);
        var views = new ArrayList<Offer>();
        for (var row : all) {
            var snapshot = snapshots.get(row.variantId());
            var variant = snapshot == null ? null : snapshot.variant();
            if (publicOnly && (variant == null || !variant.usable())) continue;
            var productImages =
                    snapshot == null
                            ? List.<sn.groupia.media.api.dto.PublicProductImage>of()
                            : images.getOrDefault(snapshot.productId(), List.of());
            String image = productImages.isEmpty() ? null : productImages.getFirst().displayUrl();
            views.add(
                    new Offer(
                            row.id(),
                            row.variantId(),
                            row.price(),
                            row.published(),
                            row.version(),
                            variant == null ? "Produit indisponible" : variant.productName(),
                            variant == null ? null : variant.productDescription(),
                            variant == null ? Map.of() : variant.attributes(),
                            variant == null ? null : variant.saleUnit(),
                            variant != null && variant.usable(),
                            image));
        }
        return new Storefront(
                store.publicIdentifier(),
                store.name(),
                "XOF",
                List.copyOf(views),
                store.pickupAddress(),
                store.pickupInstructions(),
                store.pickupVersion());
    }

    private Store requireStore(UUID organizationId, boolean lock) {
        var stores =
                jdbc.query(
                        "select id,organization_id,public_identifier,display_name,"
                                + " pickup_address,pickup_instructions,pickup_version"
                                + " from direct_storefronts where organization_id = ?"
                                + (lock ? " for update" : ""),
                        storeMapper,
                        organizationId);
        if (stores.isEmpty()) {
            throw new ApiException(
                    409, "DIRECT_STORE_REQUIRED", "Créez d'abord votre boutique directe.");
        }
        return stores.getFirst();
    }

    private String cleanName(String input) {
        if (input == null || input.isBlank()) {
            throw new ApiException(400, "INVALID_STORE_NAME", "Le nom est obligatoire.");
        }
        var value = input.strip();
        if (value.length() > 160 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(400, "INVALID_STORE_NAME", "Nom de boutique invalide.");
        }
        return value;
    }

    private void requirePrice(long amount) {
        if (amount < 1 || amount > 999999999999L) {
            throw new ApiException(400, "INVALID_DIRECT_PRICE", "Prix XOF invalide.");
        }
    }

    private ApiException unavailable() {
        return new ApiException(404, "DIRECT_STORE_UNAVAILABLE", "Boutique indisponible.");
    }
}
