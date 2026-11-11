package sn.groupia.ordering.application.service;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.grouping.api.GroupingProgressQueries;
import sn.groupia.ordering.api.CustomerOrderAccess;
import sn.groupia.ordering.api.dto.*;
import sn.groupia.ordering.application.query.CustomerAccessReadModel;
import sn.groupia.ordering.domain.repository.Orders;
import sn.groupia.shared.api.ApiException;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CustomerOrderAccessQueries implements CustomerOrderAccess {
    private final sn.groupia.payment.api.PaymentOperations payments;
    private final sn.groupia.payment.api.PaymentQuoteOperations quotes;
    private final ShippingFinance finance;
    private final sn.groupia.shipping.api.ShippingAssessments shipping;
    private final CustomerAccessReadModel read;
    private final Orders orders;
    private final GroupingProgressQueries groups;

    CustomerOrderAccessQueries(
            CustomerAccessReadModel read,
            Orders orders,
            GroupingProgressQueries groups,
            sn.groupia.shipping.api.ShippingAssessments shipping,
            ShippingFinance finance,
            sn.groupia.payment.api.PaymentOperations payments,
            sn.groupia.payment.api.PaymentQuoteOperations quotes) {
        this.quotes = quotes;
        this.payments = payments;
        this.finance = finance;
        this.shipping = shipping;
        this.read = read;
        this.orders = orders;
        this.groups = groups;
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public CustomerPaymentAttempt initiateShippingBalance(
            UUID org, UUID id, String provider, String key) {
        return initiateShippingBalance(org, id, provider, key, null);
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public CustomerPaymentAttempt initiateShippingBalance(
            UUID org, UUID id, String provider, String key, UUID feeQuoteId) {
        return initiateShippingBalance(org, id, provider, key, feeQuoteId, null);
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public CustomerPaymentAttempt initiateShippingBalance(
            UUID org, UUID id, String provider, String key, UUID feeQuoteId, String payerEmail) {
        var checkout = finance.checkout(org, id);
        // Customer access already authenticated the order session before reaching
        // this internal boundary. Money and merchant remain server authoritative.
        var context = checkout.context().withFeeQuote(feeQuoteId);
        if ("SASPAY".equalsIgnoreCase(provider) && payerEmail != null && !payerEmail.isBlank()) {
            String normalized = payerEmail.strip();
            if (normalized.length() > 254
                    || !normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
                throw new ApiException(
                        409, "SASPAY_HOSTED_CONTACT_REQUIRED",
                        "Adresse e-mail de paiement invalide.");
            context = context.withAuthorizedPayerEmail(normalized);
        }
        var attempt =
                payments.initiateRequest(
                        org,
                        checkout.requestId(),
                        provider,
                        key,
                        context);
        return new CustomerPaymentAttempt(
                attempt.id(),
                attempt.status().name(),
                attempt.expectedAmount(),
                attempt.currency(),
                attempt.reconciliationRequired(),
                attempt.initiatedAt(),
                attempt.checkoutUrl());
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public java.util.List<CustomerFeeQuote> quoteShippingBalance(UUID org, UUID id) {
        var checkout = finance.checkout(org, id);
        if (!checkout.context().saspayEligible()) return java.util.List.of();
        return quotes.quotes(org, checkout.requestId()).stream()
                .map(CustomerFeeQuote::from)
                .toList();
    }

    public CustomerPaymentOptions shippingPaymentOptions(UUID org, UUID id) {
        var order =
                orders.find(org, id)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                404,
                                                "CUSTOMER_ORDER_NOT_FOUND",
                                                "Commande inaccessible."));
        var position = finance.read(order);
        var providers =
                position.status() == ShippingSettlementView.Status.BALANCE_DUE
                                && !position.paymentInProgress()
                        ? java.util.stream.Stream.of("BICTORYS", "SASPAY")
                                .filter(payments::providerAvailable)
                                .toList()
                        : java.util.List.<String>of();
        CustomerPaymentOptions.Recovery recovery = null;
        for (var obligation : payments.shippingRequests(org, id)) {
            if (!obligation.paymentInProgress()) continue;
            var latest = payments.latestAttempt(org, obligation.request().id());
            if (latest == null || latest.attempt().reconciliationRequired()) continue;
            var a = latest.attempt();
            recovery =
                    new CustomerPaymentOptions.Recovery(
                            latest.provider(),
                            new CustomerPaymentAttempt(
                                    a.id(),
                                    a.status().name(),
                                    a.expectedAmount(),
                                    a.currency(),
                                    a.reconciliationRequired(),
                                    a.initiatedAt(),
                                    a.checkoutUrl()),
                            CustomerFeeQuote.from(latest.quote()));
        }
        return new CustomerPaymentOptions(providers, recovery);
    }

    public CustomerOrderIdentity identity(UUID org, UUID id) {
        return read.identity(org, id);
    }

    public CustomerOrderIdentity forPhone(String phone, String number) {
        return read.forPhone(phone, number);
    }

    public MyOrdersPage findOrdersForVerifiedPhone(String phone, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 50)
            throw new ApiException(400, "INVALID_REQUEST", "Pagination invalide.");
        var rows = read.list(phone, Math.multiplyExact(page, size), size);
        var details =
                groups.progressForOrders(
                        rows.stream()
                                .map(CustomerAccessReadModel.Row::campaign)
                                .collect(Collectors.toSet()));
        long count = read.count(phone);
        return new MyOrdersPage(
                rows.stream()
                        .map(
                                r ->
                                        new MyOrderSummary(
                                                r.number(),
                                                r.placedAt(),
                                                r.status(),
                                                r.fulfillment(),
                                                r.amount(),
                                                r.currency(),
                                                details.get(r.campaign())))
                        .toList(),
                page,
                size,
                count,
                (int) ((count + size - 1) / size));
    }

    public OrderTrackingView track(UUID org, UUID id) {
        var o =
                orders.find(org, id)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                404,
                                                "CUSTOMER_ORDER_NOT_FOUND",
                                                "Commande inaccessible."));
        return OrderTrackingView.from(
                OrderViews.receipt(o, null),
                groups.progress(org, o.getCampaignId()),
                shipping.currentFinal(org, id),
                finance.read(o),
                o.getReadyForPickupAt(),
                o.getPickedUpAt());
    }
}
