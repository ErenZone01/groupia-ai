package sn.groupia.ordering.domain.model;

import java.util.Set;

public enum OrderStatus {
    PENDING_PAYMENT, CONFIRMED, EXPIRED, CANCELLED, COMPLETED;

    /** COMPLETED follows confirmation and fulfilment; it retains the accepted sale history. */
    public static Set<OrderStatus> commerciallyAcceptedStatuses() {
        return Set.of(CONFIRMED, COMPLETED);
    }
}
