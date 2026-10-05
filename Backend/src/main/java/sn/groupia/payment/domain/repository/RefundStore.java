package sn.groupia.payment.domain.repository;
import java.time.Instant;
import java.util.*;
import sn.groupia.payment.domain.model.Refund;
import sn.groupia.payment.api.dto.ReserveRefund;
import sn.groupia.payment.domain.service.RefundObservation;
public interface RefundStore {
    Optional<Refund> forOrder(UUID org,UUID order);
    Optional<Refund> byReference(String reference);
    Optional<Refund> get(UUID id);
    Refund lock(UUID id);
    void coordinate(String key);
    Refund create(ReserveRefund command,Instant now);
    void state(UUID id,String status,boolean review,String reason,String transaction,Instant providerAt,Instant completedAt);
    List<Refund> history(UUID org,UUID order);
    List<Refund> reviewQueue(UUID org);
    boolean transactionTaken(String provider,String account,String transaction,UUID except);
    boolean isRefundTransaction(String provider,String account,String transaction);
    UUID receipt(String fingerprint,boolean authentic,RefundObservation observation,String error,Instant now);
    void receiptResult(UUID id,UUID refund,String status,String reason);
}

