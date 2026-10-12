package sn.groupia.ordering.application.service;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.ordering.api.dto.ShippingSettlementView;
import sn.groupia.ordering.api.dto.ShippingSettlementView.*;
import sn.groupia.ordering.domain.model.*;
import sn.groupia.ordering.domain.repository.Orders;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.shared.api.ApiException;
import sn.groupia.shipping.api.ShippingAssessments;
import sn.groupia.shipping.api.dto.ShippingAssessmentView;

/** Financial position is derived. Order lock precedes payment request locks on every write. */
@Service
public class ShippingFinance {
    private final PaymentOperations payments;
    private final ShippingAssessments shipping;
    private final Orders orders;
    private final RefundOperations refunds;
    private final PaymentCompletionFacts financialFacts;

    ShippingFinance(
            PaymentOperations payments,
            ShippingAssessments shipping,
            Orders orders,
            RefundOperations refunds,
            PaymentCompletionFacts financialFacts) {
        this.financialFacts = financialFacts;
        this.payments = payments;
        this.shipping = shipping;
        this.orders = orders;
        this.refunds = refunds;
    }

    public ShippingSettlementView read(Order order) {
        return read(order, shipping.read(OrderShipping.seed(order)));
    }

    public ShippingSettlementView read(Order order, ShippingAssessmentView assessment) {
        return position(
                order,
                assessment,
                payments.shippingRequests(order.getOrganizationId(), order.getId()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ShippingSettlementView lockPosition(Order order) {
        financialFacts.lock(order.getOrganizationId(), List.of(order.getId()));
        var states = payments.lockShippingRequests(order.getOrganizationId(), order.getId());
        return position(order, shipping.read(OrderShipping.seed(order)), states);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<PaymentObligationView> prepareCorrection(Order order) {
        financialFacts.lock(order.getOrganizationId(), List.of(order.getId()));
        if (financialRisk(order)) throw inProgress();
        var requests = payments.lockShippingRequests(order.getOrganizationId(), order.getId());
        if (requests.stream().anyMatch(PaymentObligationView::paymentInProgress))
            throw inProgress();
        return requests;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ShippingSettlementView synchronize(
            Order order, ShippingAssessmentView assessment, List<PaymentObligationView> requests) {
        var position = position(order, assessment, requests);
        String reference = reference(order, assessment);
        for (var state : requests) {
            var r = state.request();
            if (r.acceptanceStatus() == AcceptanceStatus.OPEN
                    && !r.businessReference().equals(reference))
                payments.cancelShippingRequest(order.getOrganizationId(), r.id());
        }
        if (position.status() == Status.BALANCE_DUE) {
            payments.createRequest(
                    new CreatePaymentRequest(
                            order.getOrganizationId(),
                            order.getId(),
                            PaymentPurpose.SHIPPING_BALANCE,
                            reference,
                            position.amountDue(),
                            order.getCurrency(),
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            position.amountDue(),
                            null));
        }
        return read(order, assessment);
    }

    public record Checkout(UUID organizationId, UUID requestId, PaymentCheckoutContext context) {}

    @Transactional
    public Checkout checkout(UUID org, UUID id) {
        var order = orders.find(org, id).orElseThrow(ShippingFinance::missing);
        order = orders.lock(order.getId()).orElseThrow(ShippingFinance::missing);
        if (!org.equals(order.getOrganizationId())) throw missing();
        if (order.getStatus() != OrderStatus.CONFIRMED)
            throw new ApiException(
                    409,
                    "SHIPPING_ORDER_NOT_ELIGIBLE",
                    "Cette commande ne permet pas de paiement transport.");
        var assessment = shipping.read(OrderShipping.seed(order));
        var states = payments.lockShippingRequests(org, id);
        var position = position(order, assessment, states);
        if (position.status() != Status.BALANCE_DUE)
            throw new ApiException(
                    409, "SHIPPING_BALANCE_NOT_DUE", "Aucun solde transport à payer.");
        String reference = reference(order, assessment);
        var request =
                states.stream()
                        .map(PaymentObligationView::request)
                        .filter(
                                r ->
                                        r.businessReference().equals(reference)
                                                && r.acceptanceStatus() == AcceptanceStatus.OPEN)
                        .findFirst();
        if (request.isEmpty()) {
            // Older FINALs from V1 acquire their first obligation only on an authorized payment
            // action.
            if (states.stream().anyMatch(PaymentObligationView::paymentInProgress))
                throw inProgress();
            synchronize(order, assessment, states);
            request =
                    payments.shippingRequests(org, id).stream()
                            .map(PaymentObligationView::request)
                            .filter(
                                    r ->
                                            r.businessReference().equals(reference)
                                                    && r.acceptanceStatus()
                                                            == AcceptanceStatus.OPEN)
                            .findFirst();
        }
        var contact = order.getCustomerContactSnapshot();
        return new Checkout(
                org,
                request.orElseThrow().id(),
                new PaymentCheckoutContext(
                        order.getOrderNumber(),
                        contact.firstName() + " " + contact.lastName(),
                        contact.phoneE164(),
                        contact.email()));
    }

    ShippingSettlementView position(
            Order order, ShippingAssessmentView assessment, List<PaymentObligationView> states) {
        var history = refunds.history(order.getOrganizationId(), order.getId());
        var refundAmount =
                history.stream()
                        .filter(r -> r.status().equals("SUCCEEDED"))
                        .map(
                                r -> {
                                    if (!r.currency().equals(order.getCurrency()))
                                        throw new IllegalStateException(
                                                "Inconsistent refund currency");
                                    return r.amount();
                                })
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        var summaries = history.stream().map(RefundSummary::from).toList();
        boolean stable = order.getFulfillmentStatus() == FulfillmentStatus.PICKED_UP;
        BigDecimal additional = BigDecimal.ZERO;
        for (var state : states) {
            var r = state.request();
            if (!r.organizationId().equals(order.getOrganizationId())
                    || !r.orderId().equals(order.getId())
                    || r.purpose() != PaymentPurpose.SHIPPING_BALANCE
                    || !r.currency().equals(order.getCurrency())
                    || r.goodsAmount().signum() != 0
                    || r.feeAmount().signum() != 0
                    || r.shippingAmount().compareTo(r.expectedAmount()) != 0)
                throw new IllegalStateException("Inconsistent shipping obligation");
            if (r.settledPaymentId() != null) {
                if (r.acceptanceStatus() != AcceptanceStatus.CLOSED)
                    throw new IllegalStateException("Inconsistent shipping settlement");
                additional = additional.add(r.expectedAmount());
            }
        }
        BigDecimal initial =
                order.getStatus() == OrderStatus.CONFIRMED
                                || order.getStatus() == OrderStatus.COMPLETED
                        ? order.getInitialShippingChargeAmount()
                        : BigDecimal.ZERO;
        var gross = initial.add(additional);
        var total = gross.subtract(refundAmount);
        var current = assessment.currentFinal();
        if (total.signum() < 0) throw new IllegalStateException("Refund exceeds shipping paid");
        var obligation =
                states.stream()
                        .map(PaymentObligationView::request)
                        .filter(r -> r.businessReference().equals(reference(order, assessment)))
                        .findFirst()
                        .map(
                                r ->
                                        new CurrentObligation(
                                                r.acceptanceStatus().name(), r.expectedAmount()))
                        .orElse(null);
        boolean inProgress =
                states.stream().anyMatch(PaymentObligationView::paymentInProgress)
                        || financialRisk(order);
        if (current == null)
            return new ShippingSettlementView(
                    null,
                    initial,
                    additional,
                    gross,
                    Status.NOT_FINALIZED,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    order.getCurrency(),
                    null,
                    null,
                    null,
                    inProgress,
                    refundAmount,
                    total,
                    stable,
                    summaries);
        if (!order.getCurrency().equals(current.explanation().currency()))
            throw new IllegalStateException("Inconsistent shipping currency");
        var amount = current.explanation().amount();
        var delta = amount.subtract(total);
        if (refundAmount.signum() > 0 && delta.signum() > 0)
            throw new IllegalStateException("Refund exceeds shipping credit");
        return new ShippingSettlementView(
                amount,
                initial,
                additional,
                gross,
                delta.signum() > 0
                        ? Status.BALANCE_DUE
                        : delta.signum() < 0 ? Status.CREDIT_DUE : Status.SETTLED,
                delta.max(BigDecimal.ZERO),
                delta.negate().max(BigDecimal.ZERO),
                order.getCurrency(),
                current.id(),
                current.revision(),
                obligation,
                inProgress,
                refundAmount,
                total,
                stable,
                summaries);
    }

    private boolean financialRisk(Order order) {
        return financialFacts.read(order.getOrganizationId(), order.getId()).obligations().stream()
                .anyMatch(o -> !o.risks().isEmpty() || o.pendingReceipt());
    }

    private static String reference(Order order, ShippingAssessmentView assessment) {
        return "SHIPPING_BALANCE:" + order.getId() + ":" + assessment.currentFinalCalculationId();
    }

    private static ApiException inProgress() {
        return new ApiException(
                409,
                "SHIPPING_BALANCE_PAYMENT_IN_PROGRESS",
                "Un paiement du transport est en cours de traitement. Attendez sa confirmation ou sa réconciliation avant de corriger le montant.");
    }

    private static ApiException missing() {
        return new ApiException(404, "CUSTOMER_ORDER_NOT_FOUND", "Commande inaccessible.");
    }
}
