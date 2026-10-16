package sn.groupia.subscription.domain.repository;

import java.time.Instant;
import java.util.*;
import sn.groupia.subscription.domain.model.SubscriptionInvoice;

public interface SubscriptionInvoices {
    List<SubscriptionInvoice> list(UUID organizationId);

    Optional<SubscriptionInvoice> find(UUID organizationId, UUID id);

    Optional<SubscriptionInvoice> byCreationKey(UUID organizationId, String key);

    Optional<SubscriptionInvoice> lock(UUID organizationId, UUID id);

    void insert(SubscriptionInvoice invoice);

    void expire(UUID id, Instant at);

    void markPaid(UUID id, UUID paymentId, Instant at);
}
