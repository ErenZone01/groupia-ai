package sn.groupia.directsales.application;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.catalog.api.CatalogVariants;
import sn.groupia.organization.api.OrganizationAvailability;
import sn.groupia.shared.api.ApiException;

/**
 * Anonymous direct-cart price check. This quote is an instant snapshot, not an order, reservation
 * or guaranteed price. A future checkout must revalidate under its own lock.
 */
@Service
@Transactional(readOnly = true)
public class DirectCartQuoteService {
    private final JdbcTemplate jdbc;
    private final CatalogVariants catalog;
    private final OrganizationAvailability organizations;
    private final boolean draftCreationEnabled;

    public DirectCartQuoteService(
            JdbcTemplate jdbc,
            CatalogVariants catalog,
            OrganizationAvailability organizations,
            @Value("${groupia.direct-sales.checkout.enabled:false}") boolean draftCreationEnabled) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.organizations = organizations;
        this.draftCreationEnabled = draftCreationEnabled;
    }

    public record RequestedLine(UUID offerId, Integer quantity) {}

    public record QuotedLine(
            UUID offerId,
            UUID variantId,
            int quantity,
            long unitPriceXof,
            long lineTotalXof,
            long priceRevision,
            String productName,
            Map<String, String> attributes) {}

    public record Quote(
            String currency,
            String displayName,
            long goodsSubtotalXof,
            List<QuotedLine> lines,
            boolean checkoutAvailable,
            boolean draftCreationAvailable) {}

    private record Shop(UUID id, UUID organizationId, String displayName) {}

    private record Offer(UUID id, UUID variantId, long priceXof, long revision) {}

    public Quote quote(UUID publicIdentifier, List<RequestedLine> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 20) {
            throw invalid();
        }
        var quantities = new LinkedHashMap<UUID, Integer>();
        for (var line : requested) {
            if (line == null
                    || line.offerId() == null
                    || line.quantity() == null
                    || line.quantity() < 1
                    || line.quantity() > 100
                    || quantities.putIfAbsent(line.offerId(), line.quantity()) != null) {
                throw invalid();
            }
        }
        var stores =
                jdbc.query(
                        "select id,organization_id,display_name from direct_storefronts where public_identifier=?",
                        (rs, rowNum) ->
                                new Shop(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("organization_id", UUID.class),
                                        rs.getString("display_name")),
                        publicIdentifier);
        if (stores.isEmpty() || !organizations.isActive(stores.getFirst().organizationId())) {
            throw new ApiException(404, "DIRECT_STORE_UNAVAILABLE", "Boutique indisponible.");
        }
        var store = stores.getFirst();
        String placeholders = String.join(",", Collections.nCopies(quantities.size(), "?"));
        var params = new ArrayList<Object>();
        params.add(store.id());
        params.addAll(quantities.keySet());
        var offers =
                jdbc.query(
                        "select id,product_variant_id,price_xof,version from direct_offers"
                                + " where storefront_id=? and published=true and id in ("
                                + placeholders
                                + ")",
                        (rs, rowNum) ->
                                new Offer(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("product_variant_id", UUID.class),
                                        rs.getLong("price_xof"),
                                        rs.getLong("version")),
                        params.toArray());
        if (offers.size() != quantities.size()) {
            throw stale();
        }
        var offerById = new HashMap<UUID, Offer>();
        var variantIds = new HashSet<UUID>();
        for (var offer : offers) {
            offerById.put(offer.id(), offer);
            variantIds.add(offer.variantId());
        }
        var variants = catalog.findByIds(store.organizationId(), variantIds);
        var quoteLines = new ArrayList<QuotedLine>();
        long total = 0;
        for (var selection : quantities.entrySet()) {
            var offer = offerById.get(selection.getKey());
            var variant = variants.get(offer.variantId());
            if (variant == null || !variant.usable()) throw stale();
            long lineTotal;
            try {
                lineTotal = Math.multiplyExact(offer.priceXof(), selection.getValue());
                total = Math.addExact(total, lineTotal);
            } catch (ArithmeticException ex) {
                throw invalid();
            }
            if (total > 99999999999999L) throw invalid();
            quoteLines.add(
                    new QuotedLine(
                            offer.id(),
                            offer.variantId(),
                            selection.getValue(),
                            offer.priceXof(),
                            lineTotal,
                            offer.revision(),
                            variant.productName(),
                            variant.attributes()));
        }
        // No checkout operation exists in this tranche: a quote never requests money.
        // Payment is a separate feature and remains disabled even for enabled draft creation.
        return new Quote(
                "XOF",
                store.displayName(),
                total,
                List.copyOf(quoteLines),
                false,
                draftCreationEnabled);
    }

    private ApiException invalid() {
        return new ApiException(
                400,
                "INVALID_DIRECT_CART",
                "Le panier doit contenir de 1 à 20 offres, avec des quantités de 1 à 100.");
    }

    private ApiException stale() {
        return new ApiException(
                409,
                "DIRECT_CART_CHANGED",
                "Une offre est devenue indisponible. Actualisez votre panier.");
    }
}
