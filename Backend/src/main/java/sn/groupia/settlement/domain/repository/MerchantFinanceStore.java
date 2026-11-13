package sn.groupia.settlement.domain.repository;
import java.util.*;
import java.math.BigDecimal;
import java.time.Instant;
import sn.groupia.settlement.domain.model.*;
public interface MerchantFinanceStore {
    void lockOrganization(UUID organizationId);
    void append(MerchantLedgerEntry entry);
    List<MerchantLedgerEntry> ledger(UUID organizationId);
    List<MerchantPayout> payouts(UUID organizationId);
    Optional<MerchantPayoutDestination> destination(UUID organizationId);
    void replaceDestination(MerchantPayoutDestination destination);
    Optional<MerchantPayout> byReference(UUID organizationId,String reference);
    MerchantPayout payout(UUID organizationId,UUID id);
    void create(MerchantPayout payout);
    void state(UUID id,String status,String transaction,Instant providerAt,Instant succeededAt,boolean review,String reason);
    boolean transactionTaken(String provider,String account,String transaction,UUID except);
    void lockTransaction(String provider,String account,String transaction);
}
