package sn.groupia.payment.application.service;

import java.time.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.domain.model.PaymentFeeQuote;
import sn.groupia.payment.domain.repository.PaymentQuoteStore;
import sn.groupia.shared.api.ApiException;

@Service
public class PaymentQuotes implements PaymentQuoteOperations {
    private final PaymentTransactions transactions;
    private final ProviderRegistry providers;
    private final PaymentQuoteStore store;
    private final Clock clock;

    PaymentQuotes(
            PaymentTransactions transactions,
            ProviderRegistry providers,
            PaymentQuoteStore store,
            Clock clock) {
        this.transactions = transactions;
        this.providers = providers;
        this.store = store;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.NEVER)
    public java.util.List<PaymentFeeQuoteView> quotes(UUID org, UUID requestId) {
        if (!providers.available("SASPAY")) return java.util.List.of();
        var request = transactions.request(org, requestId);
        // Order and monthly subscription obligations can share the SasPay quote contract.
        // Authorization is performed by the calling order/subscription service.
        // Direct order callers resolve their capability; claim rechecks it under order locks.
        if ((request.purpose() != PaymentPurpose.INITIAL_ORDER
                        && request.purpose() != PaymentPurpose.SUBSCRIPTION_MONTHLY
                        && request.purpose() != PaymentPurpose.DIRECT_ORDER_INITIAL
                        && request.purpose() != PaymentPurpose.SHIPPING_BALANCE)
                || request.feeAmount().signum() != 0
                || request.acceptanceStatus() != AcceptanceStatus.OPEN
                || (request.expiresAt() == null
                        && request.purpose() != PaymentPurpose.SHIPPING_BALANCE)
                || (request.expiresAt() != null && !clock.instant().isBefore(request.expiresAt())))
            throw new ApiException(
                    409,
                    "SASPAY_ORDER_NOT_ELIGIBLE",
                    "Cette commande ne permet pas ce paiement sans commission.");
        var provider = providers.require("SASPAY");
        java.util.List<sn.groupia.payment.domain.service.ProviderFee> fees;
        try {
            fees = provider.quotes(request.expectedAmount(), request.currency());
        } catch (RuntimeException unavailable) {
            throw new ApiException(
                    503,
                    "PAYMENT_QUOTE_UNAVAILABLE",
                    "Les frais ne peuvent pas être confirmés actuellement.");
        }
        var result = new java.util.ArrayList<PaymentFeeQuoteView>();
        var expiry = clock.instant().plusSeconds(120);
        if (request.expiresAt() != null && request.expiresAt().isBefore(expiry))
            expiry = request.expiresAt();
        for (var fee : fees) {
            var quote =
                    new PaymentFeeQuote(
                            UUID.randomUUID(),
                            org,
                            requestId,
                            provider.name(),
                            provider.accountReference(),
                            fee.country(),
                            fee.network(),
                            fee.label(),
                            request.expectedAmount(),
                            fee.fee(),
                            request.currency(),
                            expiry);
            store.save(quote);
            result.add(quote.view());
        }
        return java.util.List.copyOf(result);
    }
}
