package sn.groupia.subscription.application.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.payment.api.PaymentOperations;
import sn.groupia.payment.api.dto.PaymentAttemptView;

@Service
public class SubscriptionPayments {
    private final SubscriptionInvoiceService invoices;
    private final PaymentOperations payments;
    private final sn.groupia.payment.api.PaymentQuoteOperations quotes;

    SubscriptionPayments(
            SubscriptionInvoiceService invoices,
            PaymentOperations payments,
            sn.groupia.payment.api.PaymentQuoteOperations quotes) {
        this.invoices = invoices;
        this.payments = payments;
        this.quotes = quotes;
    }

    @Transactional(propagation = Propagation.NEVER)
    public java.util.List<sn.groupia.payment.api.dto.PaymentFeeQuoteView> quotes(
            UUID org, UUID invoice) {
        // Checkout verifies organization permission, subscription status and invoice expiry.
        var checkout = invoices.checkout(org, invoice, true);
        if (!checkout.context().saspayEligible()) return java.util.List.of();
        return quotes.quotes(org, checkout.paymentRequestId());
    }

    @Transactional(propagation = Propagation.NEVER)
    public PaymentAttemptView initiate(UUID org, UUID invoice, String provider, String key) {
        return initiate(org, invoice, provider, key, null);
    }

    @Transactional(propagation = Propagation.NEVER)
    public PaymentAttemptView initiate(
            UUID org, UUID invoice, String provider, String key, UUID feeQuoteId) {
        boolean saspay = "SASPAY".equalsIgnoreCase(provider);
        var checkout = invoices.checkout(org, invoice, saspay);
        if (saspay && !checkout.context().saspayEligible())
            throw new sn.groupia.shared.api.ApiException(
                    409,
                    "SASPAY_PAYER_REQUIRED",
                    "Renseignez le prénom, le nom, le téléphone sénégalais et le courriel du payeur.");
        return payments.initiateRequest(
                org,
                checkout.paymentRequestId(),
                provider,
                key,
                feeQuoteId == null
                        ? checkout.context()
                        : checkout.context().withFeeQuote(feeQuoteId));
    }
}
