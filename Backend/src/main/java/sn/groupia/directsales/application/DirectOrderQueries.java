package sn.groupia.directsales.application;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.organization.api.OrganizationAccess;
import sn.groupia.organization.api.OrganizationPermission;
import sn.groupia.shared.api.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Merchant-only, tenant-scoped read model for direct orders. Independent from the GROUPING orders
 * and their payment/fulfillment state machines.
 */
@Service
@Transactional(readOnly = true)
public class DirectOrderQueries {
    public record Summary(
            UUID id,
            String orderNumber,
            String status,
            String fulfillmentMethod,
            String currency,
            long goodsSubtotalXof,
            long platformFeeXof,
            long amountBeforeShippingXof,
            Long shippingFeeXof,
            Long acceptedTotalXof,
            Long shippingRateVersion,
            boolean shippingQuoted,
            Instant placedAt,
            int itemCount) {}

    public record Page(List<Summary> items, long total, int page, int size) {}

    public record Contact(String firstName, String lastName, String phoneE164, String email) {}

    public record Address(
            String country,
            String region,
            String city,
            String commune,
            String neighborhood,
            String details,
            String locationMethod,
            String coverageStatus,
            Double latitude,
            Double longitude) {}

    public record Item(
            int lineNumber,
            UUID offerIdSnapshot,
            UUID productIdSnapshot,
            UUID variantIdSnapshot,
            String productName,
            Map<String, String> attributes,
            String sku,
            String saleUnit,
            int quantity,
            long unitPriceXof,
            long lineTotalXof,
            long priceRevision) {}

    public record Detail(
            Summary order,
            Contact contact,
            Address deliveryAddress,
            Map<String, String> platformFeeRule,
            List<Item> items,
            long version) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OrganizationAccess access;

    public DirectOrderQueries(JdbcTemplate jdbc, ObjectMapper json, OrganizationAccess access) {
        this.jdbc = jdbc;
        this.json = json;
        this.access = access;
    }

    public Page list(UUID organizationId, int page, int size) {
        access.requirePermission(organizationId, OrganizationPermission.VIEW_ORDERS);
        if (page < 0 || page > 10000 || size < 1 || size > 50) {
            throw new ApiException(400, "INVALID_DIRECT_ORDER_PAGE", "Pagination invalide.");
        }
        Long count =
                jdbc.queryForObject(
                        "select count(*) from direct_orders where organization_id = ?",
                        Long.class,
                        organizationId);
        var summaries =
                jdbc.query(
                        "select d.id,d.order_number,d.status,d.fulfillment_method,d.currency,"
                                + "d.goods_subtotal_xof,d.platform_fee_xof,d.amount_before_shipping_xof,"
                                + "d.shipping_fee_xof,d.accepted_total_xof,d.shipping_rate_version,"
                                + "d.shipping_quoted,d.placed_at,"
                                + "(select count(*) from direct_order_items i where i.organization_id=d.organization_id"
                                + " and i.order_id=d.id) as item_count"
                                + " from direct_orders d where d.organization_id=?"
                                + " order by d.placed_at desc,d.id desc limit ? offset ?",
                        (rs, row) ->
                                summary(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("order_number"),
                                        rs.getString("status"),
                                        rs.getString("fulfillment_method"),
                                        rs.getString("currency"),
                                        rs.getLong("goods_subtotal_xof"),
                                        rs.getLong("platform_fee_xof"),
                                        rs.getLong("amount_before_shipping_xof"),
                                        rs.getObject("shipping_fee_xof", Long.class),
                                        rs.getObject("accepted_total_xof", Long.class),
                                        rs.getObject("shipping_rate_version", Long.class),
                                        rs.getBoolean("shipping_quoted"),
                                        rs.getTimestamp("placed_at").toInstant(),
                                        rs.getInt("item_count")),
                        organizationId,
                        size,
                        (long) page * size);
        return new Page(List.copyOf(summaries), count == null ? 0 : count, page, size);
    }

    public Detail get(UUID organizationId, UUID orderId) {
        access.requirePermission(organizationId, OrganizationPermission.VIEW_ORDERS);
        var matches =
                jdbc.query(
                        "select d.id,d.order_number,d.status,d.fulfillment_method,d.currency,"
                                + "d.goods_subtotal_xof,d.platform_fee_xof,d.amount_before_shipping_xof,"
                                + "d.shipping_fee_xof,d.accepted_total_xof,d.shipping_rate_version,"
                                + "d.shipping_quoted,d.placed_at,d.customer_contact_snapshot::text as contact_json,"
                                + "d.delivery_address_snapshot::text as address_json,"
                                + "d.platform_fee_rule_snapshot::text as fee_json,d.version,"
                                + "(select count(*) from direct_order_items i where i.organization_id=d.organization_id"
                                + " and i.order_id=d.id) as item_count"
                                + " from direct_orders d where d.organization_id=? and d.id=?",
                        (rs, row) -> {
                            JsonNode contact = json.readTree(rs.getString("contact_json"));
                            String addressRaw = rs.getString("address_json");
                            JsonNode address =
                                    addressRaw == null ? null : json.readTree(addressRaw);
                            return new Detail(
                                    summary(
                                            rs.getObject("id", UUID.class),
                                            rs.getString("order_number"),
                                            rs.getString("status"),
                                            rs.getString("fulfillment_method"),
                                            rs.getString("currency"),
                                            rs.getLong("goods_subtotal_xof"),
                                            rs.getLong("platform_fee_xof"),
                                            rs.getLong("amount_before_shipping_xof"),
                                            rs.getObject("shipping_fee_xof", Long.class),
                                            rs.getObject("accepted_total_xof", Long.class),
                                            rs.getObject("shipping_rate_version", Long.class),
                                            rs.getBoolean("shipping_quoted"),
                                            rs.getTimestamp("placed_at").toInstant(),
                                            rs.getInt("item_count")),
                                    new Contact(
                                            value(contact, "firstName"),
                                            value(contact, "lastName"),
                                            value(contact, "phoneE164"),
                                            value(contact, "email")),
                                    address == null
                                            ? null
                                            : new Address(
                                                    value(address, "country"),
                                                    value(address, "region"),
                                                    value(address, "city"),
                                                    value(address, "commune"),
                                                    value(address, "neighborhood"),
                                                    value(address, "details"),
                                                    value(address, "locationMethod"),
                                                    value(address, "coverageStatus"),
                                                    coordinate(address, "latitude", true),
                                                    coordinate(address, "longitude", false)),
                                    map(json.readTree(rs.getString("fee_json"))),
                                    List.of(),
                                    rs.getLong("version"));
                        },
                        organizationId,
                        orderId);
        if (matches.isEmpty()) {
            // Do not reveal existence of another organization's order.
            throw new ApiException(404, "DIRECT_ORDER_NOT_FOUND", "Commande introuvable.");
        }
        var detail = matches.getFirst();
        var lines =
                jdbc.query(
                        "select line_number,offer_id_snapshot,product_id_snapshot,product_variant_id_snapshot,"
                                + "product_name_snapshot,variant_attributes_snapshot::text as attributes_json,"
                                + "sku_snapshot,sale_unit_snapshot,quantity,unit_price_xof,"
                                + "line_total_xof,price_revision_snapshot"
                                + " from direct_order_items where organization_id=? and order_id=?"
                                + " order by line_number",
                        (rs, row) ->
                                new Item(
                                        rs.getInt("line_number"),
                                        rs.getObject("offer_id_snapshot", UUID.class),
                                        rs.getObject("product_id_snapshot", UUID.class),
                                        rs.getObject("product_variant_id_snapshot", UUID.class),
                                        rs.getString("product_name_snapshot"),
                                        map(json.readTree(rs.getString("attributes_json"))),
                                        rs.getString("sku_snapshot"),
                                        rs.getString("sale_unit_snapshot"),
                                        rs.getInt("quantity"),
                                        rs.getLong("unit_price_xof"),
                                        rs.getLong("line_total_xof"),
                                        rs.getLong("price_revision_snapshot")),
                        organizationId,
                        orderId);
        return new Detail(
                detail.order(),
                detail.contact(),
                detail.deliveryAddress(),
                detail.platformFeeRule(),
                List.copyOf(lines),
                detail.version());
    }

    private static Summary summary(
            UUID id,
            String number,
            String status,
            String fulfillment,
            String currency,
            long goods,
            long fee,
            long amount,
            Long shippingFeeXof,
            Long acceptedTotalXof,
            Long shippingRateVersion,
            boolean shippingQuoted,
            Instant placedAt,
            int count) {
        return new Summary(
                id,
                number,
                status,
                fulfillment,
                currency,
                goods,
                fee,
                amount,
                shippingFeeXof,
                acceptedTotalXof,
                shippingRateVersion,
                shippingQuoted,
                placedAt,
                count);
    }

    private static Map<String, String> map(JsonNode object) {
        var values = new HashMap<String, String>();
        object.properties().forEach(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        return Map.copyOf(values);
    }

    /**
     * Never interpret a legacy/manual address or an untrusted text field as a verified delivery
     * pin. Coordinates are exposed only in the tenant-scoped order detail.
     */
    private static Double coordinate(JsonNode address, String property, boolean latitude) {
        if (!"MAP".equals(value(address, "locationMethod"))
                || !"REGION_MATCHED".equals(value(address, "coverageStatus"))) {
            return null;
        }
        JsonNode value = address.path(property);
        if (!value.isNumber()) return null;
        double result = value.asDouble();
        return Double.isFinite(result)
                        && (latitude
                                ? result >= -90 && result <= 90
                                : result >= -180 && result <= 180)
                ? result
                : null;
    }

    private static String value(JsonNode node, String property) {
        var value = node.path(property);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }
}
