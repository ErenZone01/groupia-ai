package sn.groupia.directsales.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.payment.api.AcceptanceStatus;
import sn.groupia.payment.api.DirectPaymentClaimGuard;
import sn.groupia.payment.api.PaymentPurpose;
import sn.groupia.payment.api.PaymentTarget;
import sn.groupia.payment.api.dto.PaymentCheckoutContext;
import sn.groupia.payment.api.dto.PaymentRequestView;
import sn.groupia.shared.api.ApiException;

/**
 * Runs inside the payment claim transaction. Reuses the complete order and regional verification
 * in DirectPaymentObligations, which locks storefront THEN order before any payment-request lock.
 */
@Service
public class DirectPaymentClaimAuthorization implements DirectPaymentClaimGuard {
    record Contact(String number, String firstName, String lastName, String phone, String email) {}

    private final DirectPaymentObligations obligations;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    DirectPaymentClaimAuthorization(
            DirectPaymentObligations obligations, JdbcTemplate jdbc, Clock clock) {
        this.obligations = obligations;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentCheckoutContext authorize(
            UUID organizationId,
            UUID directOrderId,
            UUID paymentRequestId,
            BigDecimal amount,
            PaymentCheckoutContext checkoutContext) {
        if (organizationId == null || directOrderId == null || paymentRequestId == null
                || amount == null || checkoutContext == null) {
            throw blocked();
        }

        // prepare() serializes with merchant cancellation and concurrent checkouts, validates
        // immutable line prices/totals plus the current delivery region, and returns an existing
        // idempotent request. It never initiates a provider charge.
        PaymentRequestView prepared = obligations.prepare(organizationId, directOrderId);
        if (prepared == null
                || !Objects.equals(prepared.id(), paymentRequestId)
                || !Objects.equals(prepared.organizationId(), organizationId)
                || !Objects.equals(prepared.target(), PaymentTarget.directOrder(directOrderId))
                || prepared.purpose() != PaymentPurpose.DIRECT_ORDER_INITIAL
                || prepared.acceptanceStatus() != AcceptanceStatus.OPEN
                || prepared.settledPaymentId() != null
                || !"XOF".equals(prepared.currency())
                || prepared.expectedAmount() == null
                || prepared.expectedAmount().compareTo(amount) != 0
                || prepared.expiresAt() == null
                || !clock.instant().isBefore(prepared.expiresAt())) {
            throw blocked();
        }

        List<Contact> contacts = jdbc.query(
                "select d.order_number,"
                        + "d.customer_contact_snapshot->>'firstName' as first_name,"
                        + "d.customer_contact_snapshot->>'lastName' as last_name,"
                        + "d.customer_contact_snapshot->>'phoneE164' as phone,"
                        + "d.customer_contact_snapshot->>'email' as email"
                        + " from direct_orders d where d.organization_id=? and d.id=?"
                        + " and d.status='PENDING_PAYMENT_INTEGRATION'",
                (rs, row) -> new Contact(
                        rs.getString("order_number"),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getString("phone"),
                        rs.getString("email")),
                organizationId, directOrderId);
        if (contacts.size() != 1) throw blocked();
        Contact contact = contacts.getFirst();
        if (contact.number() == null
                || contact.firstName() == null || contact.firstName().isBlank()
                || contact.lastName() == null || contact.lastName().isBlank()
                || contact.phone() == null || !contact.phone().matches("\\+[1-9][0-9]{7,14}")) {
            throw blocked();
        }

        PaymentCheckoutContext canonical = new PaymentCheckoutContext(
                contact.number(),
                contact.firstName() + " " + contact.lastName(),
                contact.phone(),
                contact.email());
        if (!canonical.equals(checkoutContext)) throw blocked();
        return canonical;
    }

    private static ApiException blocked() {
        return new ApiException(
                409, "DIRECT_PAYMENT_ORDER_INELIGIBLE",
                "La commande ne remplit pas les conditions de paiement sécurisé.");
    }
}
