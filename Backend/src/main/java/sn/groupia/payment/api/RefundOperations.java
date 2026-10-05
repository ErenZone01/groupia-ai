package sn.groupia.payment.api;
import java.util.*;
import sn.groupia.payment.api.dto.*;
public interface RefundOperations {
    /** Caller holds the order lock and has recalculated the entire stable credit. */
    RefundView reserve(ReserveRefund command);
    /** Must run after the reservation transaction commits. */
    void dispatch(UUID organizationId,UUID refundId);
    List<RefundView> history(UUID organizationId,UUID orderId);
    RefundView get(UUID organizationId,UUID refundId);
    boolean payoutsEnabled();
    void reconcile(UUID organizationId,UUID refundId);
}

