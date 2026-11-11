package sn.groupia.customeraccess.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.ordering.api.CustomerOrderAccess;
import sn.groupia.ordering.api.dto.CustomerPaymentAttempt;

@Service
public class CustomerShippingPayments {
    @Transactional(propagation = Propagation.NEVER)
    public java.util.List<sn.groupia.ordering.api.dto.CustomerFeeQuote> quotes(
            String token, String number) {
        var identity = access.paymentIdentity(token, number);
        return orders.quoteShippingBalance(identity.organizationId(), identity.id());
    }

    public sn.groupia.ordering.api.dto.CustomerPaymentOptions options(String token, String number) {
        var identity = access.paymentIdentity(token, number);
        return orders.shippingPaymentOptions(identity.organizationId(), identity.id());
    }

    private final CustomerAccess access;
    private final CustomerOrderAccess orders;

    CustomerShippingPayments(CustomerAccess access, CustomerOrderAccess orders) {
        this.access = access;
        this.orders = orders;
    }

    @Transactional(propagation = Propagation.NEVER)
    public CustomerPaymentAttempt initiate(
            String token, String number, String provider, String key) {
        return initiate(token, number, provider, key, null);
    }

    @Transactional(propagation = Propagation.NEVER)
    public CustomerPaymentAttempt initiate(
            String token, String number, String provider, String key, java.util.UUID feeQuoteId) {
        return initiate(token, number, provider, key, feeQuoteId, null);
    }

    @Transactional(propagation = Propagation.NEVER)
    public CustomerPaymentAttempt initiate(
            String token, String number, String provider, String key,
            java.util.UUID feeQuoteId, String payerEmail) {
        var identity = access.paymentIdentity(token, number);
        return orders.initiateShippingBalance(
                identity.organizationId(), identity.id(), provider, key, feeQuoteId, payerEmail);
    }
}
