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

    SubscriptionPayments(SubscriptionInvoiceService invoices, PaymentOperations payments) {
        this.invoices = invoices;
        this.payments = payments;
    }

    @Transactional(propagation = Propagation.NEVER)
    public PaymentAttemptView initiate(UUID org, UUID invoice, String provider, String key) {
        var checkout = invoices.checkout(org, invoice);
        return payments.initiateRequest(
                org, checkout.paymentRequestId(), provider, key, checkout.context());
    }
}
