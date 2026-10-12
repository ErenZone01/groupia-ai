package sn.groupia.ordering.application.service;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.grouping.api.CampaignCompletion;
import sn.groupia.ordering.api.dto.GroupingCompletionView;
import sn.groupia.ordering.api.dto.GroupingCompletionView.Blocker;
import sn.groupia.ordering.domain.model.*;
import sn.groupia.ordering.domain.repository.Orders;
import sn.groupia.ordering.domain.service.GroupingCompletionPolicy;
import sn.groupia.organization.api.*;
import sn.groupia.payment.api.PaymentCompletionFacts;
import sn.groupia.shared.api.ApiException;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class GroupingCompletion {
    private final CampaignCompletion campaigns;
    private final Orders orders;
    private final OrganizationAccess access;
    private final PaymentCompletionFacts payments;
    private final ShippingFinance shipping;

    public GroupingCompletion(
            CampaignCompletion campaigns,
            Orders orders,
            OrganizationAccess access,
            PaymentCompletionFacts payments,
            ShippingFinance shipping) {
        this.campaigns = campaigns;
        this.orders = orders;
        this.access = access;
        this.payments = payments;
        this.shipping = shipping;
    }

    public GroupingCompletionView read(UUID org, UUID campaign) {
        access.requirePermission(org, OrganizationPermission.VIEW_GROUPINGS);
        access.requirePermission(org, OrganizationPermission.VIEW_ORDERS);
        access.requirePermission(org, OrganizationPermission.VIEW_PAYMENTS);
        return evaluate(
                org, campaigns.read(org, campaign), orders.forCompletion(org, campaign, false));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GroupingCompletionView complete(
            UUID org, UUID campaign, long version, boolean confirmed) {
        access.requirePermission(org, OrganizationPermission.MANAGE_GROUPINGS);
        if (!confirmed)
            throw new ApiException(
                    400,
                    "GROUPING_COMPLETION_CONFIRMATION_REQUIRED",
                    "Confirmez explicitement la fin définitive du groupage.");
        var state = campaigns.lock(org, campaign, version);
        var all = orders.forCompletion(org, campaign, true);
        payments.lock(org, all.stream().map(Order::getId).toList());
        var result = evaluate(org, state, all);
        if (!result.eligible())
            throw new ApiException(
                    409,
                    "GROUPING_COMPLETION_BLOCKED",
                    "Des opérations restent à terminer.",
                    Map.of("blockers", result.blockers()));
        var completed = campaigns.complete(org, campaign, version);
        return new GroupingCompletionView(
                false,
                false,
                completed.status(),
                completed.version(),
                completed.completedAt(),
                result.totalOrders(),
                result.completedOrders(),
                result.terminalUnexecutedOrders(),
                Map.of(),
                List.of());
    }

    private GroupingCompletionView evaluate(
            UUID org, CampaignCompletion.State state, List<Order> all) {
        var blockers = new ArrayList<Blocker>();
        if (!state.status().equals("DISTRIBUTING"))
            blockers.add(new Blocker("INVALID_GROUPING_TRANSITION", null, null));
        if (all.isEmpty()
                || all.stream()
                        .noneMatch(
                                o ->
                                        o.getStatus() == OrderStatus.CONFIRMED
                                                || o.getStatus() == OrderStatus.COMPLETED))
            blockers.add(new Blocker("GROUPING_COMPLETION_INCONSISTENT_STATE", null, null));
        for (var o : all) {
            var facts = payments.read(org, o.getId());
            try {
                var position =
                        o.getStatus() == OrderStatus.COMPLETED
                                        || o.getStatus() == OrderStatus.CONFIRMED
                                ? shipping.read(o)
                                : null;
                blockers.addAll(GroupingCompletionPolicy.evaluate(o, facts, position));
            } catch (IllegalStateException ex) {
                blockers.add(
                        new Blocker(
                                "GROUPING_COMPLETION_INCONSISTENT_STATE",
                                o.getId(),
                                o.getOrderNumber()));
            }
        }
        return new GroupingCompletionView(
                blockers.isEmpty(),
                access.hasPermission(org, OrganizationPermission.MANAGE_GROUPINGS),
                state.status(),
                state.version(),
                state.completedAt(),
                all.size(),
                all.stream().filter(o -> o.getStatus() == OrderStatus.COMPLETED).count(),
                all.stream()
                        .filter(
                                o ->
                                        o.getStatus() == OrderStatus.EXPIRED
                                                || o.getStatus() == OrderStatus.CANCELLED)
                        .count(),
                blockers.stream()
                        .collect(
                                Collectors.groupingBy(
                                        Blocker::code, TreeMap::new, Collectors.counting())),
                List.copyOf(blockers));
    }
}
