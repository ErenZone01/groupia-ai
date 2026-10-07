package sn.groupia.settlement.domain.service;
import java.math.BigDecimal;
import java.time.Instant;
import sn.groupia.settlement.domain.model.MerchantPayout;
/** Initiation is never proof. Implementations must authenticate server evidence before returning Proof.
 * No browser endpoint accepts a Proof. Real settlement integration is deliberately unavailable in V1. */
public interface MerchantPayoutProvider {
    boolean enabled();
    String name();
    String account();
    String initiate(MerchantPayout payout);
    Proof verify(MerchantPayout payout);
    record Proof(boolean authenticated,String provider,String account,String businessReference,String transactionId,
        BigDecimal amount,String currency,String method,String phoneE164,String status,Instant occurredAt) {
        @Override public String toString() { return "MerchantPayoutProof[redacted]"; }
    }
}
