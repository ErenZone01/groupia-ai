package sn.groupia.directsales.application;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.directsales.api.DirectOrderRecovery;
import sn.groupia.shared.api.ApiException;

/**
 * Direct orders can be recovered after OTP ownership of the checkout phone.
 * Security identity is provided by the customeraccess module, not by public input.
 */
@Service
public class DirectOrderRecoveryService implements DirectOrderRecovery {
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public DirectOrderRecoveryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Page forVerifiedPhone(String phone, int page, int size) {
        if (phone == null || !phone.matches("\\+[1-9][0-9]{7,14}")) {
            throw inaccessible();
        }
        if (page < 0 || page > 10000 || size < 1 || size > 20) {
            throw new ApiException(400, "INVALID_DIRECT_RECOVERY_PAGE", "Pagination invalide.");
        }
        Long total = jdbc.queryForObject(
                "select count(*) from direct_orders"
                        + " where customer_contact_snapshot->>'phoneE164'=?",
                Long.class,
                phone);
        List<Entry> items = jdbc.query(
                "select d.order_number,s.public_identifier,d.status,d.fulfillment_method,"
                        + " d.currency,d.accepted_total_xof,d.placed_at"
                        + " from direct_orders d"
                        + " join direct_storefronts s on s.id=d.storefront_id"
                        + " and s.organization_id=d.organization_id"
                        + " where d.customer_contact_snapshot->>'phoneE164'=?"
                        + " order by d.placed_at desc,d.id desc limit ? offset ?",
                (rs, row) -> new Entry(
                        rs.getString("order_number"),
                        rs.getObject("public_identifier", UUID.class),
                        rs.getString("status"),
                        rs.getString("fulfillment_method"),
                        rs.getString("currency"),
                        rs.getObject("accepted_total_xof", Long.class),
                        rs.getTimestamp("placed_at").toInstant()),
                phone, size, page * size);
        return new Page(List.copyOf(items), total == null ? 0 : total, page, size);
    }

    @Override
    @Transactional
    public Rotated rotateForVerifiedPhone(String phone, String number) {
        if (phone == null || !phone.matches("\\+[1-9][0-9]{7,14}")
                || number == null || !number.matches("DIR-[0-9]{12,19}")) {
            throw inaccessible();
        }
        var rows = jdbc.query(
                "select d.id,s.public_identifier from direct_orders d"
                        + " join direct_storefronts s on s.id=d.storefront_id"
                        + " and s.organization_id=d.organization_id"
                        + " where d.customer_contact_snapshot->>'phoneE164'=?"
                        + " and d.order_number=? for update of d",
                (rs, row) -> new UUID[] {
                    rs.getObject("id", UUID.class),
                    rs.getObject("public_identifier", UUID.class)
                },
                phone, number);
        if (rows.isEmpty()) throw inaccessible();

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // Atomic replacement immediately revokes the previous private link.
        int updated = jdbc.update(
                "update direct_orders set recovery_token_hash=?,version=version+1"
                        + " where id=?",
                DirectAccessTokens.hashForCreate(token), rows.getFirst()[0]);
        if (updated != 1) throw inaccessible();
        return new Rotated(number, rows.getFirst()[1], token);
    }

    private static ApiException inaccessible() {
        return new ApiException(404, "DIRECT_ORDER_NOT_FOUND", "Commande introuvable.");
    }
}
