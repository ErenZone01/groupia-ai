package sn.groupia.directsales.application;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.payment.api.PaymentOperations;
import sn.groupia.payment.api.dto.PaymentAttemptView;
import sn.groupia.payment.api.dto.PaymentCheckoutContext;
import sn.groupia.shared.api.ApiException;

/**
 * Customer capability-only checkout initiation. Does not accept an order id, organization id,
 * provider, price, currency or buyer identity from the browser. The payment module independently
 * locks and revalidates every claim after this read-only token lookup.
 */
@Service
public class DirectCustomerPaymentInitiation {
    record Order(
            UUID organizationId,
            UUID id,
            UUID requestId,
            String orderNumber,
            String status,
            String currency,
            Long acceptedTotal,
            String firstName,
            String lastName,
            String phone,
            String email) {}

    private final JdbcTemplate jdbc;
    private final PaymentOperations payments;
    private final boolean enabled;

    DirectCustomerPaymentInitiation(
            JdbcTemplate jdbc,
            PaymentOperations payments,
            @Value("${groupia.direct-sales.payment-initiation.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.enabled = enabled;
    }

    @Transactional(propagation = Propagation.NEVER)
    public PaymentAttemptView initiate(String token, String idempotencyKey) {
        String tokenHash = DirectAccessTokens.hashForLookup(token);
        if (!enabled) {
            throw new ApiException(
                    409,
                    "DIRECT_PAYMENT_NOT_ENABLED",
                    "Le paiement des commandes directes n'est pas encore disponible.");
        }
        if (idempotencyKey == null || !idempotencyKey.strip().matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new ApiException(
                    400,
                    "PAYMENT_IDEMPOTENCY_KEY_REQUIRED",
                    "Clé d'initiation invalide ou absente.");
        }

        List<Order> orders =
                jdbc.query(
                        "select d.organization_id,d.id,d.order_number,d.status,d.currency,"
                                + "d.accepted_total_xof,p.id as payment_request_id,"
                                + "d.customer_contact_snapshot->>'firstName' as first_name,"
                                + "d.customer_contact_snapshot->>'lastName' as last_name,"
                                + "d.customer_contact_snapshot->>'phoneE164' as phone,"
                                + "d.customer_contact_snapshot->>'email' as email"
                                + " from direct_orders d"
                                + " left join payment_requests p on p.organization_id=d.organization_id"
                                + " and p.direct_order_id=d.id and p.purpose='DIRECT_ORDER_INITIAL'"
                                + " where d.recovery_token_hash=?",
                        (rs, row) ->
                                new Order(
                                        rs.getObject("organization_id", UUID.class),
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("payment_request_id", UUID.class),
                                        rs.getString("order_number"),
                                        rs.getString("status"),
                                        rs.getString("currency"),
                                        rs.getObject("accepted_total_xof", Long.class),
                                        rs.getString("first_name"),
                                        rs.getString("last_name"),
                                        rs.getString("phone"),
                                        rs.getString("email")),
                        tokenHash);
        if (orders.size() != 1) throw notFound();
        Order order = orders.getFirst();
        if (order.requestId() == null
                || !"PENDING_PAYMENT_INTEGRATION".equals(order.status())
                || !"XOF".equals(order.currency())
                || order.acceptedTotal() == null
                || order.acceptedTotal() <= 0
                || order.acceptedTotal() > 99999999999999L
                || order.firstName() == null
                || order.firstName().isBlank()
                || order.lastName() == null
                || order.lastName().isBlank()
                || order.phone() == null
                || !order.phone().matches("\\+[1-9][0-9]{7,14}")) {
            throw new ApiException(
                    409,
                    "DIRECT_PAYMENT_ORDER_INELIGIBLE",
                    "Cette commande n'est pas éligible au paiement.");
        }
        PaymentCheckoutContext context =
                new PaymentCheckoutContext(
                        order.orderNumber(),
                        order.firstName() + " " + order.lastName(),
                        order.phone(),
                        order.email());
        // Service opens a transaction for the durable claim, then calls Bictorys only
        // after its commit. A changed order/token/amount must never cause a charge.
        return payments.initiateDirect(
                order.organizationId(),
                order.requestId(),
                idempotencyKey.strip(),
                tokenHash,
                context);
    }

    private static ApiException notFound() {
        return new ApiException(404, "DIRECT_ORDER_NOT_FOUND", "Commande introuvable.");
    }
}
