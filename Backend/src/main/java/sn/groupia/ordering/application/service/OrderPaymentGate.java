package sn.groupia.ordering.application.service;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.ordering.domain.model.OrderStatus;
import sn.groupia.ordering.domain.repository.Orders;
import sn.groupia.shared.api.ApiException;

@Service
class OrderPaymentGate {
    private final sn.groupia.grouping.api.CampaignCancellationFacts cancellations;
    private final Orders orders;
    private final CheckoutSecrets secrets;
    private final Clock clock;

    OrderPaymentGate(
            Orders orders,
            CheckoutSecrets secrets,
            Clock clock,
            sn.groupia.grouping.api.CampaignCancellationFacts cancellations) {
        this.cancellations = cancellations;
        this.orders = orders;
        this.secrets = secrets;
        this.clock = clock;
    }

    record Context(
            UUID organizationId,
            UUID orderId,
            sn.groupia.payment.api.dto.PaymentCheckoutContext checkout) {}

    @Transactional(readOnly = true)
    public Context authorize(String number, String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw invalid();
        }
        var order =
                orders.findByTrackingHash(secrets.hash(token))
                        .filter(
                                o ->
                                        o.allowsTrackingAt(clock.instant())
                                                && o.getOrderNumber().equals(number))
                        .orElseThrow(this::invalid);
        if (order.getStatus() == OrderStatus.EXPIRED
                || !clock.instant().isBefore(order.getPaymentDeadline())) {
            throw new ApiException(
                    409, "ORDER_PAYMENT_EXPIRED", "Le délai de paiement est dépassé.");
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ApiException(
                    409, "ORDER_NOT_PAYABLE", "La commande ne permet pas une initiation.");
        }
        if (cancellations
                .cancellation(order.getOrganizationId(), order.getCampaignId())
                .cancelled())
            throw new ApiException(409, "ORDER_NOT_PAYABLE", "Le groupage est annulé.");
        var contact = order.getCustomerContactSnapshot();
        return new Context(
                order.getOrganizationId(),
                order.getId(),
                new sn.groupia.payment.api.dto.PaymentCheckoutContext(
                        order.getOrderNumber(),
                        contact.firstName() + " " + contact.lastName(),
                        contact.phoneE164(),
                        contact.email()));
    }

    private ApiException invalid() {
        return new ApiException(
                404, "ORDER_TRACKING_INVALID", "Accès de suivi invalide ou expiré.");
    }
}
