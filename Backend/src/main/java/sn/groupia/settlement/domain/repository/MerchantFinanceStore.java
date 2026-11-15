package sn.groupia.settlement.domain.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import sn.groupia.settlement.domain.model.*;

public interface MerchantFinanceStore {
    void lockOrganization(UUID organizationId);

    /** Atomically lease a bounded batch; never selects intents without a provider reference. */
    record VerificationWork(MerchantPayout payout, UUID claimToken) {}

    List<VerificationWork> dueForVerification(String provider, String account);

    void requestVerification(String provider, String account, String transactionId);

    /** Remove scheduling work only after a terminal state has been independently verified. */
    void finishVerification(UUID payoutId, UUID claimToken);

    void append(MerchantLedgerEntry entry);

    List<MerchantLedgerEntry> ledger(UUID organizationId);

    List<MerchantPayout> payouts(UUID organizationId);

    Optional<MerchantPayoutDestination> destination(UUID organizationId);

    void replaceDestination(MerchantPayoutDestination destination);

    Optional<MerchantPayout> byReference(UUID organizationId, String reference);

    MerchantPayout payout(UUID organizationId, UUID id);

    void create(MerchantPayout payout);

    /** Append-only SasPay fee evidence; never withdraw the fee from a seller ledger. */
    void recordProviderFee(UUID payoutId, BigDecimal principal, BigDecimal providerFee);

    /** Historical ADD_ON proof remains unchanged; new deductions preserve gross and net. */
    void recordDeductedProviderFee(UUID payoutId, BigDecimal gross, BigDecimal providerFee);

    void createFeeQuote(MerchantPayoutFeeQuote quote);

    MerchantPayoutFeeQuote feeQuote(UUID organizationId, UUID quoteId);

    MerchantPayoutFeeQuote payoutFeeQuote(UUID payoutId);

    Optional<MerchantPayoutFeeQuote> findPayoutFeeQuote(UUID payoutId);

    void bindFeeQuote(MerchantPayoutFeeQuote quote, MerchantPayout payout);

    /** Company-owned XOF fee budget, never a seller balance. Caller must hold a transaction. */
    void reserveCompanyFee(String providerAccount, UUID payoutId, BigDecimal maxFee);

    void settleCompanyFee(String providerAccount, UUID payoutId, BigDecimal chargedFee);

    void state(
            UUID id,
            String status,
            String transaction,
            Instant providerAt,
            Instant succeededAt,
            boolean review,
            String reason);

    boolean transactionTaken(String provider, String account, String transaction, UUID except);

    void lockTransaction(String provider, String account, String transaction);
}
