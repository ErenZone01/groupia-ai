package sn.groupia.directsales.application;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.catalog.api.CatalogVariants;
import sn.groupia.organization.api.OrganizationAvailability;
import sn.groupia.shared.api.ApiException;
import sn.groupia.subscription.api.SubscriptionEntitlements;
import tools.jackson.databind.ObjectMapper;

/**
 * Separate, payment-disabled direct-order foundation. Never uses the GROUPING order
 * aggregate, and never creates a Bictorys payment request.
 */
@Service
public class DirectCheckoutService {
    public record Address(
            String country,
            String region,
            String city,
            String commune,
            String neighborhood,
            String details) {}

    public record Contact(
            String firstName,
            String lastName,
            String phone,
            String email,
            Address deliveryAddress) {}

    public record Line(
            UUID offerId,
            Integer quantity,
            Long expectedUnitPriceXof,
            Long expectedPriceRevision) {}

    public record Command(Contact contact, String fulfillmentMethod, List<Line> lines) {}

    public record Receipt(
            String orderNumber,
            String status,
            String currency,
            String fulfillmentMethod,
            long goodsSubtotalXof,
            long platformFeeXof,
            long amountBeforeShippingXof,
            boolean shippingQuoted,
            boolean paymentAvailable) {}

    public record Result(boolean created, Receipt receipt) {}

    private record Store(UUID id, UUID organizationId) {}

    private record NormalizedContact(
            String firstName, String lastName, String phoneE164, String email) {}

    private final JdbcTemplate jdbc;
    private final DirectCartQuoteService carts;
    private final CatalogVariants catalog;
    private final OrganizationAvailability organizations;
    private final SubscriptionEntitlements entitlements;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean enabled;
    private final long platformFeeXof;

    DirectCheckoutService(
            JdbcTemplate jdbc,
            DirectCartQuoteService carts,
            CatalogVariants catalog,
            OrganizationAvailability organizations,
            SubscriptionEntitlements entitlements,
            ObjectMapper json,
            Clock clock,
            @Value("${groupia.direct-sales.checkout.enabled:false}") boolean enabled,
            @Value("${groupia.ordering.platform-fee-xof}") long platformFeeXof) {
        if (platformFeeXof < 0 || platformFeeXof > 99999999999999L) {
            throw new IllegalArgumentException("Invalid direct sales platform fee");
        }
        this.jdbc = jdbc;
        this.carts = carts;
        this.catalog = catalog;
        this.organizations = organizations;
        this.entitlements = entitlements;
        this.json = json;
        this.clock = clock;
        this.enabled = enabled;
        this.platformFeeXof = platformFeeXof;
    }

    @Transactional(timeout = 15)
    public Result checkout(UUID publicIdentifier, String rawKey, Command command) {
        // The public checkout is never activated by the frontend in this tranche.
        if (!enabled) {
            throw new ApiException(
                    503, "DIRECT_CHECKOUT_DISABLED", "La commande directe n'est pas encore activée.");
        }
        String key = validateKey(rawKey);
        List<Line> lines = validateLines(command);
        String method = validateMethod(command.fulfillmentMethod());

        // Lock serializes direct checkout against offer publication/price updates, which
        // also lock the storefront row. It also protects the idempotent insert race.
        var stores = jdbc.query(
                "select id,organization_id from direct_storefronts"
                        + " where public_identifier=? for update",
                (rs, row) -> new Store(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class)),
                publicIdentifier);
        if (stores.isEmpty() || !organizations.isActive(stores.getFirst().organizationId())) {
            throw new ApiException(404, "DIRECT_STORE_UNAVAILABLE", "Boutique indisponible.");
        }
        var store = stores.getFirst();
        entitlements.requireNewBusinessAllowed(store.organizationId());
        var contact = validateContact(command.contact(), organizations.countryCode(store.organizationId()));
        Address delivery = validateAddress(command.contact().deliveryAddress(), method);
        var fingerprint = sha256(json.writeValueAsString(Arrays.asList(
                "direct-checkout-v1", publicIdentifier, contact, method, delivery, lines)));

        var existing = jdbc.query(
                "select order_number,checkout_request_fingerprint,status,fulfillment_method,"
                        + "goods_subtotal_xof,platform_fee_xof,amount_before_shipping_xof,"
                        + "shipping_quoted from direct_orders"
                        + " where organization_id=? and checkout_idempotency_key=?",
                (rs, row) -> new Object[] {
                    rs.getString("checkout_request_fingerprint"),
                    new Receipt(
                            rs.getString("order_number"),
                            rs.getString("status"),
                            "XOF",
                            rs.getString("fulfillment_method"),
                            rs.getLong("goods_subtotal_xof"),
                            rs.getLong("platform_fee_xof"),
                            rs.getLong("amount_before_shipping_xof"),
                            rs.getBoolean("shipping_quoted"),
                            false)
                },
                store.organizationId(),
                key);
        if (!existing.isEmpty()) {
            Object[] saved = existing.getFirst();
            if (!fingerprint.equals(saved[0])) {
                throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED",
                        "Cette clé a déjà servi à une autre commande.");
            }
            return new Result(false, (Receipt) saved[1]);
        }

        var selections = lines.stream()
                .map(line -> new DirectCartQuoteService.RequestedLine(line.offerId(), line.quantity()))
                .toList();
        var quote = carts.quote(publicIdentifier, selections);
        Map<UUID, DirectCartQuoteService.QuotedLine> actual = quote.lines().stream()
                .collect(Collectors.toMap(DirectCartQuoteService.QuotedLine::offerId, item -> item));
        for (var line : lines) {
            var found = actual.get(line.offerId());
            if (found == null
                    || found.unitPriceXof() != line.expectedUnitPriceXof()
                    || found.priceRevision() != line.expectedPriceRevision()) {
                throw new ApiException(409, "DIRECT_PRICE_CHANGED",
                        "Le prix ou la disponibilité a changé. Actualisez votre panier.");
            }
        }
        long amount;
        try {
            amount = Math.addExact(quote.goodsSubtotalXof(), platformFeeXof);
        } catch (ArithmeticException ex) {
            throw new ApiException(400, "INVALID_DIRECT_TOTAL", "Montant non valide.");
        }
        if (amount > 99999999999999L) {
            throw new ApiException(400, "INVALID_DIRECT_TOTAL", "Montant trop élevé.");
        }
        var snapshots = catalog.commercialSnapshots(
                store.organizationId(),
                quote.lines().stream()
                        .map(DirectCartQuoteService.QuotedLine::variantId)
                        .collect(Collectors.toSet()));
        if (snapshots.size() != quote.lines().size()) {
            throw new ApiException(409, "DIRECT_CART_CHANGED", "Le catalogue a changé.");
        }

        UUID orderId = UUID.randomUUID();
        Instant now = clock.instant();
        Long sequence = jdbc.queryForObject(
                "select nextval('direct_order_number_seq')", Long.class);
        String number = String.format(Locale.ROOT, "DIR-%012d", sequence);
        var feeRule = Map.of(
                "type", "FIXED", "currency", "XOF",
                "amount", Long.toString(platformFeeXof), "version", "1");
        boolean pickup = method.equals("PICKUP");
        jdbc.update(
                "insert into direct_orders"
                        + " (id,organization_id,storefront_id,order_number,status,"
                        + "fulfillment_method,currency,customer_contact_snapshot,"
                        + "delivery_address_snapshot,goods_subtotal_xof,platform_fee_xof,"
                        + "platform_fee_rule_snapshot,amount_before_shipping_xof,"
                        + "shipping_quoted,checkout_idempotency_key,checkout_request_fingerprint,placed_at)"
                        + " values (?,?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,?,"
                        + "cast(? as jsonb),?,?,?,?,?)",
                orderId,
                store.organizationId(),
                store.id(),
                number,
                "PENDING_PAYMENT_INTEGRATION",
                method,
                "XOF",
                json.writeValueAsString(contact),
                pickup ? null : json.writeValueAsString(delivery),
                quote.goodsSubtotalXof(),
                platformFeeXof,
                json.writeValueAsString(feeRule),
                amount,
                pickup,
                key,
                fingerprint,
                java.sql.Timestamp.from(now));
        int position = 0;
        for (var line : quote.lines()) {
            var snapshot = snapshots.get(line.variantId());
            if (snapshot == null || snapshot.variant() == null || !snapshot.variant().usable()) {
                throw new ApiException(409, "DIRECT_CART_CHANGED", "Le catalogue a changé.");
            }
            jdbc.update(
                    "insert into direct_order_items"
                            + " (id,organization_id,order_id,offer_id_snapshot,product_id_snapshot,"
                            + "product_variant_id_snapshot,product_name_snapshot,"
                            + "variant_attributes_snapshot,sku_snapshot,sale_unit_snapshot,"
                            + "line_number,quantity,unit_price_xof,line_total_xof,price_revision_snapshot)"
                            + " values (?,?,?,?,?,?,?,cast(? as jsonb),?,?,?,?,?,?,?)",
                    UUID.randomUUID(),
                    store.organizationId(),
                    orderId,
                    line.offerId(),
                    snapshot.productId(),
                    line.variantId(),
                    line.productName(),
                    json.writeValueAsString(line.attributes()),
                    snapshot.sku(),
                    snapshot.variant().saleUnit(),
                    ++position,
                    line.quantity(),
                    line.unitPriceXof(),
                    line.lineTotalXof(),
                    line.priceRevision());
        }
        return new Result(
                true,
                new Receipt(
                        number,
                        "PENDING_PAYMENT_INTEGRATION",
                        "XOF",
                        method,
                        quote.goodsSubtotalXof(),
                        platformFeeXof,
                        amount,
                        pickup,
                        false));
    }

    private String validateKey(String value) {
        if (value == null || !value.strip().matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new ApiException(400, "INVALID_IDEMPOTENCY_KEY", "Clé de commande invalide.");
        }
        return value.strip();
    }

    private List<Line> validateLines(Command command) {
        if (command == null || command.lines() == null
                || command.lines().isEmpty() || command.lines().size() > 20) {
            throw new ApiException(400, "INVALID_DIRECT_CART", "Panier invalide.");
        }
        var ids = new java.util.HashSet<UUID>();
        var sorted = new ArrayList<Line>();
        for (var line : command.lines()) {
            if (line == null || line.offerId() == null
                    || line.quantity() == null || line.quantity() < 1 || line.quantity() > 100
                    || line.expectedUnitPriceXof() == null || line.expectedUnitPriceXof() < 1
                    || line.expectedPriceRevision() == null || line.expectedPriceRevision() < 0
                    || !ids.add(line.offerId())) {
                throw new ApiException(400, "INVALID_DIRECT_CART", "Lignes du panier invalides.");
            }
            sorted.add(line);
        }
        sorted.sort(Comparator.comparing(line -> line.offerId().toString()));
        return List.copyOf(sorted);
    }

    private String validateMethod(String method) {
        if (!"PICKUP".equals(method) && !"DELIVERY".equals(method)) {
            throw new ApiException(400, "INVALID_DIRECT_FULFILLMENT", "Mode de retrait invalide.");
        }
        return method;
    }

    private NormalizedContact validateContact(Contact contact, String countryCode) {
        if (contact == null) {
            throw invalidContact();
        }
        String first = required(contact.firstName(), 100);
        String last = required(contact.lastName(), 100);
        String email = contact.email() == null || contact.email().isBlank()
                ? null : contact.email().strip().toLowerCase(Locale.ROOT);
        if (email != null && (email.length() > 254
                || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                || email.codePoints().anyMatch(Character::isISOControl))) {
            throw invalidContact();
        }
        String phone = contact.phone();
        if (phone == null || phone.length() > 40
                || !phone.matches("\\+?[0-9() .-]+")) {
            throw invalidContact();
        }
        try {
            var util = PhoneNumberUtil.getInstance();
            var parsed = util.parse(phone, countryCode);
            if (parsed.hasExtension() || !util.isValidNumber(parsed)) {
                throw invalidContact();
            }
            return new NormalizedContact(
                    first, last,
                    util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164), email);
        } catch (NumberParseException ex) {
            throw invalidContact();
        }
    }

    private Address validateAddress(Address address, String method) {
        if (method.equals("PICKUP")) {
            if (address != null) throw invalidContact();
            return null;
        }
        if (address == null || !"SN".equals(address.country())) {
            throw invalidContact();
        }
        return new Address(
                "SN",
                required(address.region(), 100),
                required(address.city(), 100),
                required(address.commune(), 120),
                optional(address.neighborhood(), 160),
                required(address.details(), 500));
    }

    private String required(String raw, int max) {
        String s = raw == null ? "" : raw.strip();
        if (s.isBlank() || s.length() > max
                || s.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidContact();
        }
        return s;
    }

    private String optional(String raw, int max) {
        if (raw == null || raw.isBlank()) return null;
        return required(raw, max);
    }

    private ApiException invalidContact() {
        return new ApiException(400, "INVALID_CUSTOMER_CONTACT", "Coordonnées client invalides.");
    }

    private String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
