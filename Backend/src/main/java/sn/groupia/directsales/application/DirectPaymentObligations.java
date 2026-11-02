package sn.groupia.directsales.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.organization.api.OrganizationAvailability;
import sn.groupia.payment.api.PaymentOperations;
import sn.groupia.payment.api.dto.CreatePaymentRequest;
import sn.groupia.payment.api.dto.PaymentRequestView;
import sn.groupia.shared.api.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Internal financial preparation for direct sales. The caller must be an authorized direct-sales
 * application flow, never an arbitrary public payment target. This service creates an immutable
 * obligation only: payment initiation remains blocked in PaymentTransactions.claim.
 */
@Service
public class DirectPaymentObligations {
    private static final Duration ACCEPTANCE_WINDOW = Duration.ofHours(24);
    private static final long MAX_TOTAL = 99999999999999L;

    private record Snapshot(
            UUID id,
            String status,
            String currency,
            String method,
            long goods,
            long fee,
            long beforeShipping,
            boolean shippingQuoted,
            Long shippingFee,
            Long acceptedTotal,
            Long shippingRateVersion,
            String address,
            Instant placedAt) {}

    private final JdbcTemplate jdbc;
    private final PaymentOperations payments;
    private final DirectDeliveryPolicyService delivery;
    private final DirectRegionGeometry geometry;
    private final OrganizationAvailability organizations;
    private final ObjectMapper json;
    private final Clock clock;

    DirectPaymentObligations(
            JdbcTemplate jdbc,
            PaymentOperations payments,
            DirectDeliveryPolicyService delivery,
            DirectRegionGeometry geometry,
            OrganizationAvailability organizations,
            ObjectMapper json,
            Clock clock) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.delivery = delivery;
        this.geometry = geometry;
        this.organizations = organizations;
        this.json = json;
        this.clock = clock;
    }

    /**
     * No HTTP endpoint uses this command. Storefront locking precedes the order lock to serialize
     * against merchant delivery-policy edits and checkout. The payment request is idempotent by
     * business reference and a unique partial index for the direct-order target.
     */
    @Transactional(timeout = 15)
    public PaymentRequestView prepare(UUID organizationId, UUID orderId) {
        if (organizationId == null || orderId == null) throw notFound();
        List<UUID> storeIds =
                jdbc.query(
                        "select s.id from direct_storefronts s"
                                + " join direct_orders d on d.organization_id=s.organization_id"
                                + " and d.storefront_id=s.id"
                                + " where d.organization_id=? and d.id=? for update of s",
                        (rs, row) -> rs.getObject("id", UUID.class),
                        organizationId,
                        orderId);
        if (storeIds.isEmpty() || !organizations.isActive(organizationId)) {
            throw notFound();
        }
        UUID storefrontId = storeIds.getFirst();
        List<Snapshot> rows =
                jdbc.query(
                        "select id,status,currency,fulfillment_method,goods_subtotal_xof,"
                                + "platform_fee_xof,amount_before_shipping_xof,shipping_quoted,"
                                + "shipping_fee_xof,accepted_total_xof,shipping_rate_version,"
                                + "delivery_address_snapshot::text as address_json,placed_at"
                                + " from direct_orders where organization_id=? and id=?"
                                + " and storefront_id=? for update",
                        (rs, row) ->
                                new Snapshot(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("status"),
                                        rs.getString("currency"),
                                        rs.getString("fulfillment_method"),
                                        rs.getLong("goods_subtotal_xof"),
                                        rs.getLong("platform_fee_xof"),
                                        rs.getLong("amount_before_shipping_xof"),
                                        rs.getBoolean("shipping_quoted"),
                                        rs.getObject("shipping_fee_xof", Long.class),
                                        rs.getObject("accepted_total_xof", Long.class),
                                        rs.getObject("shipping_rate_version", Long.class),
                                        rs.getString("address_json"),
                                        rs.getTimestamp("placed_at").toInstant()),
                        organizationId,
                        orderId,
                        storefrontId);
        if (rows.isEmpty()) throw notFound();
        Snapshot saved = rows.getFirst();
        Instant deadline = saved.placedAt().plus(ACCEPTANCE_WINDOW);
        if (!"PENDING_PAYMENT_INTEGRATION".equals(saved.status())
                || !"XOF".equals(saved.currency())
                || !clock.instant().isBefore(deadline)) {
            throw ineligible();
        }
        if (!saved.shippingQuoted()
                || saved.shippingFee() == null
                || saved.acceptedTotal() == null
                || saved.goods() <= 0
                || saved.fee() < 0
                || saved.shippingFee() < 0
                || saved.shippingFee() > 100_000
                || saved.acceptedTotal() <= 0
                || saved.acceptedTotal() > MAX_TOTAL) {
            throw ineligible();
        }
        long subtotal;
        long total;
        try {
            subtotal = Math.addExact(saved.goods(), saved.fee());
            total = Math.addExact(subtotal, saved.shippingFee());
        } catch (ArithmeticException ex) {
            throw ineligible();
        }
        if (subtotal != saved.beforeShipping() || total != saved.acceptedTotal()) {
            throw ineligible();
        }

        if ("PICKUP".equals(saved.method())) {
            if (saved.address() != null
                    || saved.shippingFee() != 0
                    || saved.shippingRateVersion() != null) {
                throw ineligible();
            }
        } else if ("DELIVERY".equals(saved.method())) {
            validateDelivery(organizationId, storefrontId, saved);
        } else {
            throw ineligible();
        }

        Long itemCount =
                jdbc.queryForObject(
                        "select count(*) from direct_order_items"
                                + " where organization_id=? and order_id=?",
                        Long.class,
                        organizationId,
                        orderId);
        BigDecimal linesTotal =
                jdbc.queryForObject(
                        "select coalesce(sum(line_total_xof),0) from direct_order_items"
                                + " where organization_id=? and order_id=?",
                        BigDecimal.class,
                        organizationId,
                        orderId);
        if (itemCount == null
                || itemCount < 1
                || itemCount > 20
                || linesTotal == null
                || linesTotal.compareTo(BigDecimal.valueOf(saved.goods())) != 0) {
            throw ineligible();
        }

        // Every amount is from the persisted server-accepted snapshot, never a browser total.
        return payments.createRequest(
                CreatePaymentRequest.forDirectOrder(
                        organizationId,
                        saved.id(),
                        BigDecimal.valueOf(saved.goods()),
                        BigDecimal.valueOf(saved.fee()),
                        BigDecimal.valueOf(saved.shippingFee()),
                        deadline));
    }

    private void validateDelivery(UUID org, UUID storefrontId, Snapshot saved) {
        if (saved.address() == null
                || saved.shippingRateVersion() == null
                || saved.shippingRateVersion() < 0) {
            throw ineligible();
        }
        JsonNode address;
        try {
            address = json.readTree(saved.address());
        } catch (RuntimeException ex) {
            throw ineligible();
        }
        if (!"SN".equals(address.path("country").asText())
                || !"MAP".equals(address.path("locationMethod").asText())
                || !"REGION_MATCHED".equals(address.path("coverageStatus").asText())
                || !address.path("latitude").isNumber()
                || !address.path("longitude").isNumber()) {
            throw ineligible();
        }
        String region = address.path("region").asText();
        var matched =
                geometry.locate(
                        address.path("latitude").asDouble(),
                        address.path("longitude").asDouble());
        if (!"FOUND".equals(matched.status()) || !Objects.equals(region, matched.region())) {
            throw ineligible();
        }
        delivery.requireDeliveryAllowed(org, storefrontId, region);
    }

    private static ApiException ineligible() {
        return new ApiException(
                409,
                "DIRECT_PAYMENT_ORDER_INELIGIBLE",
                "Cette commande ne peut pas encore être préparée au paiement.");
    }

    private static ApiException notFound() {
        return new ApiException(404, "DIRECT_ORDER_NOT_FOUND", "Commande introuvable.");
    }
}
