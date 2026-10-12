package sn.groupia.payment.api;

import java.util.*;
import sn.groupia.payment.api.dto.OrderPaymentCompletionFacts;

public interface PaymentCompletionFacts {
    /** Caller holds every order lock; locks all requests before all refunds. */
    void lock(UUID organizationId, List<UUID> orderedOrderIds);

    OrderPaymentCompletionFacts read(UUID organizationId, UUID orderId);
}
