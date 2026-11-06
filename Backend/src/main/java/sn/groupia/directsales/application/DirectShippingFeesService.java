package sn.groupia.directsales.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.organization.api.OrganizationAccess;
import sn.groupia.organization.api.OrganizationPermission;
import sn.groupia.shared.api.ApiException;
import sn.groupia.subscription.api.CommercialRevenuePolicy;
import tools.jackson.databind.ObjectMapper;

/**
 * Optional regional delivery-price preview. No price sent by the buyer is trusted; this service
 * never creates a direct order or authorizes a payment.
 */
@Service
public class DirectShippingFeesService {
    public record Settings(Map<String, Long> regionFeesXof, long version) {}

    /** Read inside the storefront row lock held by DirectCheckoutService. */
    public record CheckoutFee(long amountXof, long rateVersion) {}

    public CheckoutFee forCheckout(UUID organizationId, UUID storefrontId, String region) {
        Settings settings = read(organizationId, storefrontId);
        Long rate = settings.regionFeesXof().get(region);
        if (rate == null) {
            throw new ApiException(
                    409,
                    "DIRECT_SHIPPING_FEE_UNCONFIGURED",
                    "La livraison doit être tarifée avant de confirmer le total.");
        }
        return new CheckoutFee(rate, settings.version());
    }

    public record Destination(Double latitude, Double longitude) {}

    public record PreviewRequest(
            String fulfillmentMethod,
            List<DirectCartQuoteService.RequestedLine> lines,
            String region,
            Destination deliveryPoint) {}

    public record Preview(
            String status,
            String currency,
            String region,
            String locationVerification,
            long goodsSubtotalXof,
            long platformFeeXof,
            Long shippingFeeXof,
            Long totalXof,
            boolean shippingQuoted,
            boolean paymentAvailable) {}

    private static final Set<String> REGIONS =
            Set.of(
                    "Dakar",
                    "Diourbel",
                    "Fatick",
                    "Kaffrine",
                    "Kaolack",
                    "Kédougou",
                    "Kolda",
                    "Louga",
                    "Matam",
                    "Saint-Louis",
                    "Sédhiou",
                    "Tambacounda",
                    "Thiès",
                    "Ziguinchor");
    private static final long MAX_FEE = 100_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final OrganizationAccess access;
    private final DirectStorefrontService stores;
    private final DirectDeliveryPolicyService policies;
    private final DirectRegionGeometry boundaries;
    private final DirectCartQuoteService carts;
    private final long platformFeeXof;

    public DirectShippingFeesService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            Clock clock,
            OrganizationAccess access,
            DirectStorefrontService stores,
            DirectDeliveryPolicyService policies,
            DirectRegionGeometry boundaries,
            DirectCartQuoteService carts,
            CommercialRevenuePolicy pricing) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.access = access;
        this.stores = stores;
        this.policies = policies;
        this.boundaries = boundaries;
        this.carts = carts;
        this.platformFeeXof = pricing.transactionFeeXofLong();
    }

    @Transactional(readOnly = true)
    public Settings manager(UUID organizationId) {
        access.requirePermission(organizationId, OrganizationPermission.VIEW_CATALOG);
        return read(organizationId, requireStore(organizationId, false));
    }

    @Transactional
    public Settings save(UUID organizationId, Settings proposed) {
        access.requirePermission(organizationId, OrganizationPermission.MANAGE_CATALOG);
        if (proposed == null || proposed.version() < 0 || proposed.regionFeesXof() == null) {
            throw invalid();
        }
        var validated = new TreeMap<String, Long>();
        if (proposed.regionFeesXof().size() > REGIONS.size()) throw invalid();
        for (var entry : proposed.regionFeesXof().entrySet()) {
            if (!REGIONS.contains(entry.getKey())
                    || entry.getValue() == null
                    || entry.getValue() < 0
                    || entry.getValue() > MAX_FEE) throw invalid();
            validated.put(entry.getKey(), entry.getValue());
        }
        // This lock also serializes changes to the delivery-coverage policy and new drafts.
        UUID storeId = requireStore(organizationId, true);
        Settings current = read(organizationId, storeId);
        if (current.version() != proposed.version()) throw conflict();
        boolean exists =
                !jdbc.query(
                                "select 1 from direct_shipping_fee_settings"
                                        + " where organization_id=? and storefront_id=?",
                                (rs, row) -> rs.getInt(1),
                                organizationId,
                                storeId)
                        .isEmpty();
        if (!exists) {
            jdbc.update(
                    "insert into direct_shipping_fee_settings"
                            + " (organization_id,storefront_id,region_fees,version,updated_at)"
                            + " values (?,?,cast(? as jsonb),1,?)",
                    organizationId,
                    storeId,
                    json.writeValueAsString(validated),
                    Timestamp.from(clock.instant()));
        } else {
            int updated =
                    jdbc.update(
                            "update direct_shipping_fee_settings"
                                    + " set region_fees=cast(? as jsonb),version=version+1,updated_at=?"
                                    + " where organization_id=? and storefront_id=? and version=?",
                            json.writeValueAsString(validated),
                            Timestamp.from(clock.instant()),
                            organizationId,
                            storeId,
                            proposed.version());
            if (updated != 1) throw conflict();
        }
        return read(organizationId, storeId);
    }

    @Transactional(readOnly = true)
    public Preview preview(UUID publicIdentifier, PreviewRequest request) {
        if (request == null) throw invalid();
        // Existing catalog quote always validates published offers and current prices.
        var quote = carts.quote(publicIdentifier, request.lines());
        if (request.fulfillmentMethod() == null
                || !Set.of("PICKUP", "DELIVERY").contains(request.fulfillmentMethod()))
            throw invalid();
        long base;
        try {
            base = Math.addExact(quote.goodsSubtotalXof(), platformFeeXof);
        } catch (ArithmeticException ex) {
            throw invalid();
        }
        if ("PICKUP".equals(request.fulfillmentMethod())) {
            if (request.region() != null || request.deliveryPoint() != null) throw invalid();
            return new Preview(
                    "QUOTED",
                    "XOF",
                    null,
                    "NONE",
                    quote.goodsSubtotalXof(),
                    platformFeeXof,
                    0L,
                    base,
                    true,
                    false);
        }
        if ((request.region() == null) == (request.deliveryPoint() == null)) throw invalid();

        // Published shop and policy are resolved independently from client-provided data.
        var policy = policies.publicPolicy(publicIdentifier);
        String region;
        String verification;
        if (request.deliveryPoint() != null) {
            var point = request.deliveryPoint();
            if (point.latitude() == null || point.longitude() == null) throw invalid();
            var match = boundaries.locate(point.latitude(), point.longitude());
            if ("INVALID".equals(match.status())) throw invalid();
            if (!"FOUND".equals(match.status())) {
                return unquoted(match.status(), null, "MAP_UNCERTAIN", quote, platformFeeXof);
            }
            region = match.region();
            verification = "MAP_REGION_MATCHED";
        } else {
            region = request.region();
            if (region == null || !REGIONS.contains(region)) throw invalid();
            verification = "MANUAL_UNVERIFIED";
        }

        if ("DISABLED".equals(policy.mode())) {
            return unquoted("DELIVERY_DISABLED", region, verification, quote, platformFeeXof);
        }
        if ("SELECTED".equals(policy.mode()) && !policy.allowedRegions().contains(region)) {
            return unquoted("OUTSIDE_ZONE", region, verification, quote, platformFeeXof);
        }

        Settings settings = publicSettings(publicIdentifier);
        Long fee = settings.regionFeesXof().get(region);
        if (fee == null) {
            return unquoted("FEE_UNCONFIGURED", region, verification, quote, platformFeeXof);
        }
        long total;
        try {
            total = Math.addExact(base, fee);
        } catch (ArithmeticException ex) {
            throw invalid();
        }
        return new Preview(
                "QUOTED",
                "XOF",
                region,
                verification,
                quote.goodsSubtotalXof(),
                platformFeeXof,
                fee,
                total,
                true,
                false);
    }

    private Preview unquoted(
            String status,
            String region,
            String verification,
            DirectCartQuoteService.Quote quote,
            long platformFee) {
        return new Preview(
                status,
                "XOF",
                region,
                verification,
                quote.goodsSubtotalXof(),
                platformFee,
                null,
                null,
                false,
                false);
    }

    private Settings publicSettings(UUID publicIdentifier) {
        var ids =
                jdbc.query(
                        "select id,organization_id from direct_storefronts where public_identifier=?",
                        (rs, row) ->
                                new UUID[] {
                                    rs.getObject("id", UUID.class),
                                    rs.getObject("organization_id", UUID.class)
                                },
                        publicIdentifier);
        if (ids.isEmpty()) {
            throw new ApiException(404, "DIRECT_STORE_UNAVAILABLE", "Boutique indisponible.");
        }
        return read(ids.getFirst()[1], ids.getFirst()[0]);
    }

    private UUID requireStore(UUID organizationId, boolean lock) {
        var rows =
                jdbc.query(
                        "select id from direct_storefronts where organization_id=?"
                                + (lock ? " for update" : ""),
                        (rs, row) -> rs.getObject("id", UUID.class),
                        organizationId);
        if (rows.isEmpty())
            throw new ApiException(
                    409, "DIRECT_STORE_REQUIRED", "Créez d'abord votre boutique directe.");
        return rows.getFirst();
    }

    private Settings read(UUID organizationId, UUID storeId) {
        var rows =
                jdbc.query(
                        "select region_fees::text as fees,version"
                                + " from direct_shipping_fee_settings"
                                + " where organization_id=? and storefront_id=?",
                        (rs, row) -> {
                            var root = json.readTree(rs.getString("fees"));
                            var fees = new TreeMap<String, Long>();
                            for (String region : REGIONS) {
                                var value = root.path(region);
                                if (value.isIntegralNumber()) fees.put(region, value.asLong());
                            }
                            return new Settings(Map.copyOf(fees), rs.getLong("version"));
                        },
                        organizationId,
                        storeId);
        return rows.isEmpty() ? new Settings(Map.of(), 0) : rows.getFirst();
    }

    private ApiException invalid() {
        return new ApiException(
                400,
                "INVALID_DIRECT_SHIPPING_FEES",
                "Tarifs ou destination de livraison invalides.");
    }

    private ApiException conflict() {
        return new ApiException(
                409,
                "DIRECT_SHIPPING_FEES_CONFLICT",
                "Les tarifs ont changé. Rechargez la page avant d'enregistrer.");
    }
}
