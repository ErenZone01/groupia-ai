package sn.groupia.directsales.application;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.shared.api.ApiException;

/**
 * Customer-owned, unguessable link access to a deliberately minimal read model. The token hash is
 * the only selector; neither order number nor phone grants access.
 */
@Service
@Transactional(readOnly = true)
public class DirectOrderTrackingService {
    public record Tracking(
            String orderNumber,
            String status,
            String fulfillmentMethod,
            String currency,
            long goodsSubtotalXof,
            long platformFeeXof,
            long amountBeforeShippingXof,
            Long shippingFeeXof,
            Long acceptedTotalXof,
            boolean paymentAvailable,
            Instant placedAt,
            DirectOrderProgress progress,
            String pickupAddress,
            String pickupInstructions) {}

    private final JdbcTemplate jdbc;

    public DirectOrderTrackingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Tracking lookup(String token) {
        String hash = DirectAccessTokens.hashForLookup(token);
        var found =
                jdbc.query(
                        "select order_number,status,fulfillment_method,currency,"
                                + "goods_subtotal_xof,platform_fee_xof,amount_before_shipping_xof,"
                                + "shipping_fee_xof,accepted_total_xof,placed_at,"
                                + "payment_confirmed_at,preparing_at,ready_at,dispatched_at,completed_at,"
                                + "pickup_address_snapshot,pickup_instructions_snapshot"
                                + " from direct_orders where recovery_token_hash=?",
                        (rs, row) ->
                                new Tracking(
                                        rs.getString("order_number"),
                                        rs.getString("status"),
                                        rs.getString("fulfillment_method"),
                                        rs.getString("currency"),
                                        rs.getLong("goods_subtotal_xof"),
                                        rs.getLong("platform_fee_xof"),
                                        rs.getLong("amount_before_shipping_xof"),
                                        rs.getObject("shipping_fee_xof", Long.class),
                                        rs.getObject("accepted_total_xof", Long.class),
                                        false,
                                        rs.getTimestamp("placed_at").toInstant(),
                                        DirectOrderProgress.read(rs),
                                        rs.getString("pickup_address_snapshot"),
                                        rs.getString("pickup_instructions_snapshot")),
                        hash);
        if (found.isEmpty()) {
            throw new ApiException(404, "DIRECT_ORDER_NOT_FOUND", "Commande introuvable.");
        }
        return found.getFirst();
    }
}
