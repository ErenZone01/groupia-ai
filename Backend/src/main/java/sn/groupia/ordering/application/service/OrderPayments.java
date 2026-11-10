package sn.groupia.ordering.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.payment.api.PaymentOperations;
import sn.groupia.payment.api.dto.PaymentAttemptView;

@Service
public class OrderPayments {
    private final OrderPaymentGate gate;
    private final PaymentOperations payments;
    private final sn.groupia.payment.api.PaymentQuoteOperations quotes;

    OrderPayments(
            OrderPaymentGate gate,
            PaymentOperations payments,
            sn.groupia.payment.api.PaymentQuoteOperations quotes) {
        this.gate = gate;
        this.payments = payments;
        this.quotes = quotes;
    }

    @Transactional(propagation = Propagation.NEVER)
    public java.util.List<sn.groupia.payment.api.dto.PaymentFeeQuoteView> quotes(
            String number, String token) {
        var context = gate.authorize(number, token);
        if (context.checkout().email() == null
                || context.checkout().email().isBlank()
                || context.checkout().phone() == null
                || !context.checkout().phone().matches("\\+221[0-9]{9}"))
            return java.util.List.of();
        return quotes.quotes(
                context.organizationId(),
                payments.initialRequest(context.organizationId(), context.orderId()).id());
    }

    @Transactional(propagation = Propagation.NEVER)
    public PaymentAttemptView initiate(String number, String token, String provider, String key) {
        return initiate(number, token, provider, key, null);
    }

    @Transactional(propagation = Propagation.NEVER)
    public PaymentAttemptView initiate(
            String number, String token, String provider, String key, java.util.UUID quoteId) {
        return initiate(number, token, provider, key, quoteId, null);
    }

    @Transactional(propagation = Propagation.NEVER)
    public PaymentAttemptView initiate(
            String number, String token, String provider, String key,
            java.util.UUID quoteId, String payerEmail) {
        var context = gate.authorize(number, token);
        var paymentContext = context.checkout().withFeeQuote(quoteId);
        // A private order token is checked before an optional payer email is read.
        // Never alter immutable order contact snapshots or monetary obligations.
        if ("SASPAY".equalsIgnoreCase(provider) && payerEmail != null && !payerEmail.isBlank()) {
            String normalized = payerEmail.strip();
            if (normalized.length() > 254
                    || !normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
                throw new sn.groupia.shared.api.ApiException(
                        409, "SASPAY_HOSTED_CONTACT_REQUIRED",
                        "Adresse e-mail de paiement invalide.");
            paymentContext = paymentContext.withAuthorizedPayerEmail(normalized);
        }
        return payments.initiateInitial(
                context.organizationId(), context.orderId(), provider, key, paymentContext);
    }
}
