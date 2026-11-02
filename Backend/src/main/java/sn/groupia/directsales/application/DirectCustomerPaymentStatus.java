package sn.groupia.directsales.application;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.shared.api.ApiException;

/**
 * Minimal bearer-token-scoped payment preparation status for a direct-order customer. The token
 * hash is the only selector: neither order number, merchant ID nor payment request ID grants access.
 * Preparation is not a payment, so paymentAvailable stays false in every response.
 */
@Service
@Transactional(readOnly = true)
public class DirectCustomerPaymentStatus {
    public record Status(
            String orderNumber,
            String currency,
            Long acceptedTotalXof,
            String preparationStatus,
            boolean paymentAvailable) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DirectCustomerPaymentStatus(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Status lookup(String token) {
        String hash = DirectAccessTokens.hashForLookup(token);
        List<Status> matches =
                jdbc.query(
                        "select d.order_number,d.currency,d.accepted_total_xof,"
                                + " p.expected_amount,p.acceptance_status,p.expires_at"
                                + " from direct_orders d"
                                + " left join payment_requests p on p.organization_id=d.organization_id"
                                + " and p.direct_order_id=d.id and p.purpose='DIRECT_ORDER_INITIAL'"
                                + " where d.recovery_token_hash=?",
                        (rs, row) -> {
                            Long accepted = rs.getObject("accepted_total_xof", Long.class);
                            String acceptance = rs.getString("acceptance_status");
                            BigDecimal expected = rs.getBigDecimal("expected_amount");
                            Timestamp expires = rs.getTimestamp("expires_at");
                            String preparation = preparation(accepted, acceptance, expected, expires);
                            return new Status(
                                    rs.getString("order_number"),
                                    rs.getString("currency"),
                                    accepted,
                                    preparation,
                                    false);
                        },
                        hash);
        if (matches.isEmpty()) {
            throw new ApiException(404, "DIRECT_ORDER_NOT_FOUND", "Commande introuvable.");
        }
        return matches.getFirst();
    }

    private String preparation(
            Long accepted, String acceptance, BigDecimal expected, Timestamp expires) {
        if (acceptance == null) {
            return "NOT_PREPARED";
        }
        if (accepted == null
                || expected == null
                || expected.compareTo(BigDecimal.valueOf(accepted)) != 0
                || !"OPEN".equals(acceptance)
                || expires == null) {
            return "UNAVAILABLE";
        }
        return clock.instant().isBefore(expires.toInstant()) ? "PREPARED" : "EXPIRED";
    }
}
