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
    private final sn.groupia.payment.api.PaymentQuoteOperations quotes;

    DirectCustomerPaymentInitiation(
            JdbcTemplate jdbc,
            PaymentOperations payments,
            @Value("${groupia.direct-sales.payment-initiation.enabled:false}") boolean enabled) {
        this(jdbc, payments, enabled, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    DirectCustomerPaymentInitiation(
            JdbcTemplate jdbc,
            PaymentOperations payments,
            @Value("${groupia.direct-sales.payment-initiation.enabled:false}") boolean enabled,
            sn.groupia.payment.api.PaymentQuoteOperations quotes) {
        this.quotes = quotes;
        this.jdbc = jdbc;
        this.payments = payments;
        this.enabled = enabled;
    }

    @Transactional(propagation = Propagation.NEVER)
    public PaymentAttemptView initiate(String token, String idempotencyKey) {
        return initiate(token, idempotencyKey, null);
    }

    @Transactional(propagation = Propagation.NEVER)
    public PaymentAttemptView initiate(String token, String idempotencyKey, UUID feeQuoteId) {
        String tokenHash = DirectAccessTokens.hashForLookup(token);
        requireEnabled();
        if (idempotencyKey == null || !idempotencyKey.strip().matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new ApiException(
                    400,
                    "PAYMENT_IDEMPOTENCY_KEY_REQUIRED",
                    "Clé d'initiation invalide ou absente.");
        }
        Order order = lookup(tokenHash);
        var context = context(order).withFeeQuote(feeQuoteId);
        if (feeQuoteId != null && !context.saspayEligible())
            throw new ApiException(
                    409,
                    "SASPAY_PAYER_REQUIRED",
                    "Coordonnées du payeur incompatibles avec SasPay.");
        return payments.initiateDirect(
                order.organizationId(),
                order.requestId(),
                idempotencyKey.strip(),
                tokenHash,
                context);
    }

    @Transactional(propagation = Propagation.NEVER)
    public PaymentAttemptView initiate(
            String token, String idempotencyKey, UUID unusedFeeQuoteId, String provider) {
        return initiate(token, idempotencyKey, unusedFeeQuoteId, provider, null);
    }

    @Transactional(propagation = Propagation.NEVER)
    public PaymentAttemptView initiate(
            String token,
            String idempotencyKey,
            UUID unusedFeeQuoteId,
            String provider,
            String payerEmail) {
        String tokenHash = DirectAccessTokens.hashForLookup(token);
        requireEnabled();
        if (idempotencyKey == null || !idempotencyKey.strip().matches("[A-Za-z0-9._:-]{16,128}"))
            throw new ApiException(
                    400, "PAYMENT_IDEMPOTENCY_KEY_REQUIRED", "Clé d'initiation invalide.");
        if (!"SASPAY".equals(provider) && !"BICTORYS".equals(provider))
            throw new ApiException(
                    409, "PAYMENT_PROVIDER_NOT_ALLOWED", "Prestataire non autorisé.");
        if (unusedFeeQuoteId != null)
            throw new ApiException(
                    409, "PAYMENT_QUOTE_OBSOLETE", "Le devis par réseau n'est plus utilisé.");
        Order order = lookup(tokenHash);
        var context = context(order);
        if ("SASPAY".equals(provider)) {
            String effectiveEmail =
                    payerEmail == null || payerEmail.isBlank()
                            ? context.email()
                            : payerEmail.strip();
            if (effectiveEmail == null
                    || effectiveEmail.length() > 254
                    || !effectiveEmail.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
                throw new ApiException(
                        409,
                        "SASPAY_HOSTED_CONTACT_REQUIRED",
                        "Une adresse e-mail valide est requise pour le paiement SasPay.");
            if (payerEmail != null && !payerEmail.isBlank())
                context = context.withPayerEmail(effectiveEmail);
        }
        return payments.initiateDirect(
                order.organizationId(),
                order.requestId(),
                provider,
                idempotencyKey.strip(),
                tokenHash,
                context);
    }

    @Transactional(propagation = Propagation.NEVER)
    public List<sn.groupia.payment.api.dto.PaymentFeeQuoteView> quotes(String token) {
        String tokenHash = DirectAccessTokens.hashForLookup(token);
        requireEnabled();
        Order order = lookup(tokenHash);
        if (!context(order).saspayEligible())
            throw new ApiException(
                    409,
                    "SASPAY_PAYER_REQUIRED",
                    "Pour SasPay, prénom, nom, téléphone sénégalais et email valides sont requis.");

        return quotes.quotes(order.organizationId(), order.requestId());
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ApiException(
                    409,
                    "DIRECT_PAYMENT_NOT_ENABLED",
                    "Le paiement des commandes directes n'est pas encore disponible.");
        }
    }

    private Order lookup(String tokenHash) {
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
        return order;
    }

    private PaymentCheckoutContext context(Order order) {
        return new PaymentCheckoutContext(
                order.orderNumber(),
                order.firstName() + " " + order.lastName(),
                order.phone(),
                order.email());
    }

    private static ApiException notFound() {
        return new ApiException(404, "DIRECT_ORDER_NOT_FOUND", "Commande introuvable.");
    }
}
