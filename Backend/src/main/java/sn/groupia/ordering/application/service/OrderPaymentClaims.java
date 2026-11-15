package sn.groupia.ordering.application.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.grouping.api.CampaignCancellationFacts;
import sn.groupia.ordering.domain.model.OrderStatus;
import sn.groupia.ordering.domain.repository.Orders;
import sn.groupia.payment.api.*;
import sn.groupia.shared.api.ApiException;

@Service
public class OrderPaymentClaims implements OrderPaymentClaimGuard {
    private final Orders orders;
    private final CampaignCancellationFacts campaigns;

    public OrderPaymentClaims(Orders o, CampaignCancellationFacts c) {
        orders = o;
        campaigns = c;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockAndCanInitiate(UUID org, UUID id, PaymentPurpose purpose) {
        var found = orders.find(org, id).orElseThrow(OrderPaymentClaims::missing);
        var campaign = campaigns.lockForOrderWorkflow(org, found.getCampaignId());
        var order = orders.lock(id).orElseThrow(OrderPaymentClaims::missing);
        if (!order.getOrganizationId().equals(org)) throw missing();
        if (campaign.status().equals("CANCELLED")) return false;
        return switch (purpose) {
            case INITIAL_ORDER -> order.getStatus() == OrderStatus.PENDING_PAYMENT;
            case SHIPPING_BALANCE ->
                    order.getStatus() == OrderStatus.CONFIRMED
                            || order.getStatus() == OrderStatus.COMPLETED;
            default -> false;
        };
    }

    private static ApiException missing() {
        return new ApiException(404, "ORDER_NOT_FOUND", "Commande introuvable.");
    }
}
