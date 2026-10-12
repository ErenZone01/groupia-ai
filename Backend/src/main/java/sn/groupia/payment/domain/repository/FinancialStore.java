package sn.groupia.payment.domain.repository;

import java.time.Instant;
import java.util.*;
import sn.groupia.payment.domain.model.*;

public interface FinancialStore {
    List<PaymentRequest> allRequests(UUID org, UUID order);

    boolean pendingReceipts(UUID request);

    void coordinate(String key);

    void add(PaymentRequest request);

    void add(Payment payment);

    void add(PaymentWebhookEvent event);

    void flush();

    Optional<PaymentRequest> byBusinessReference(String reference);

    Optional<PaymentRequest> initial(UUID org, UUID order);

    List<PaymentRequest> shippingRequests(UUID org, UUID order);

    Optional<PaymentRequest> request(UUID id);

    PaymentRequest lockRequest(UUID id);

    Optional<Payment> attempt(UUID request, String key);

    List<Payment> attempts(UUID request);

    Optional<Payment> payment(UUID id);

    Optional<Payment> byMerchant(String reference);

    Optional<Payment> byProviderTransaction(String provider, String account, String transaction);

    Payment lockPayment(UUID id);

    Optional<PaymentWebhookEvent> byWebhookKey(String key);

    PaymentWebhookEvent lockWebhook(UUID id);

    Optional<PaymentWebhookEvent> webhook(UUID id);

    RequestPage list(UUID org, int page, int size);

    record RequestPage(List<PaymentRequest> items, long total) {}

    List<UUID> retryableWebhooks(Instant now, int limit);

    List<UUID> uncertainPayments(int limit);
}
