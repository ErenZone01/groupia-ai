package sn.groupia.directsales.application;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional handoff from a newly persisted direct order to its verified financial obligation.
 * This does not initiate a payment or change the customer's paymentAvailable flag.
 */
@Service
class DirectCheckoutPaymentPreparation {
    private final DirectPaymentObligations obligations;
    private final boolean enabled;

    DirectCheckoutPaymentPreparation(
            DirectPaymentObligations obligations,
            @Value("${groupia.direct-sales.payment-obligation.auto-prepare.enabled:false}")
                    boolean enabled) {
        this.obligations = obligations;
        this.enabled = enabled;
    }

    /**
     * Invoked only after both the order and all its item snapshots have been persisted. Never
     * invoked on an idempotent replay; disabling this switch preserves existing checkout behavior.
     * The financial service revalidates all amounts and geographic evidence from the database.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void afterCreated(
            UUID organizationId,
            UUID orderId,
            String fulfillmentMethod,
            boolean mapRegionVerified,
            Long acceptedTotalXof) {
        if (!enabled || acceptedTotalXof == null) {
            return;
        }
        boolean payableLocation =
                "PICKUP".equals(fulfillmentMethod)
                        || ("DELIVERY".equals(fulfillmentMethod) && mapRegionVerified);
        if (!payableLocation) {
            return;
        }
        // Any failure must roll back the new order and its items in the same transaction.
        obligations.prepare(organizationId, orderId);
    }
}
