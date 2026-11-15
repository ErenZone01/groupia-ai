package sn.groupia.settlement.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import sn.groupia.grouping.api.MerchantCampaignFacts;
import sn.groupia.organization.api.OrganizationAccess;
import sn.groupia.payment.api.OutgoingPayoutOperations;
import sn.groupia.settlement.api.dto.CampaignSettlement;
import sn.groupia.settlement.api.dto.MerchantBalance;
import sn.groupia.settlement.domain.model.MerchantPayout;
import sn.groupia.settlement.domain.model.MerchantPayoutFeeQuote;
import sn.groupia.settlement.domain.repository.MerchantFinanceStore;
import sn.groupia.settlement.domain.service.MerchantPayoutProvider;
import sn.groupia.shared.api.ApiException;

class MerchantPayoutFeeBudgetTests {
    private final MerchantFinanceStore store = mock(MerchantFinanceStore.class);
    private final MerchantPayoutProvider provider = mock(MerchantPayoutProvider.class);
    private final CampaignSettlement campaignPosition = mock(CampaignSettlement.class);
    private final UUID org = UUID.randomUUID();
    private final UUID payoutId = UUID.randomUUID();
    private final String account = UUID.randomUUID().toString();
    private final MerchantPayout payout =
            new MerchantPayout(
                    payoutId,
                    org,
                    UUID.randomUUID(),
                    new BigDecimal("1000"),
                    "XOF",
                    "CREATED",
                    UUID.randomUUID(),
                    "WAVE",
                    "+221771234567",
                    "Boutique pilote",
                    "SASPAY",
                    account,
                    "MERCHANT_PAYOUT:" + org + ":" + UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    UUID.randomUUID(),
                    Instant.parse("2026-11-11T00:00:00Z"),
                    null,
                    null,
                    true,
                    "AWAITING_DISPATCH",
                    0);

    private MerchantPayoutTransactions transactions() {
        when(store.payout(org, payoutId)).thenReturn(payout);
        when(provider.enabled()).thenReturn(true);
        when(provider.name()).thenReturn("SASPAY");

        // Claim rechecks actual settled/reserved campaign funds before any provider POST.
        var balances = mock(MerchantBalances.class);
        var balance = mock(MerchantBalance.class);
        when(campaignPosition.campaignId()).thenReturn(payout.campaignId());
        when(campaignPosition.eligibleBalance()).thenReturn(new BigDecimal("1000"));
        when(campaignPosition.reservedForPayout()).thenReturn(new BigDecimal("1000"));
        when(campaignPosition.deficit()).thenReturn(BigDecimal.ZERO);
        when(balance.campaigns()).thenReturn(List.of(campaignPosition));
        when(balance.eligibleBalance()).thenReturn(new BigDecimal("1000"));
        when(balance.reservedForPayout()).thenReturn(new BigDecimal("1000"));
        when(balances.calculate(org, null, true, false, true)).thenReturn(balance);
        when(balances.calculate(org, payout.campaignId(), true, false, true)).thenReturn(balance);

        var transactions =
                new MerchantPayoutTransactions(
                        store,
                        balances,
                        mock(DirectMerchantBalances.class),
                        mock(MerchantCampaignFacts.class),
                        mock(OrganizationAccess.class),
                        provider,
                        Clock.systemUTC(),
                        mock(OutgoingPayoutOperations.class),
                        true,
                        false);
        ReflectionTestUtils.setField(transactions, "saspayPayoutPilotEnabled", true);
        ReflectionTestUtils.setField(
                transactions, "saspayPayoutPilotOrganizationId", org.toString());
        ReflectionTestUtils.setField(
                transactions, "saspayPayoutPilotMaxXof", new BigDecimal("1000"));
        return transactions;
    }

    private MerchantPayoutFeeQuote approvedQuote() {
        return new MerchantPayoutFeeQuote(
                UUID.randomUUID(),
                org,
                payout.campaignId(),
                null,
                payout.destinationId(),
                payout.method(),
                payout.phoneE164(),
                "SASPAY",
                account,
                payout.amount(),
                new BigDecimal("20"),
                new BigDecimal("980"),
                Instant.parse("2026-11-15T00:00:00Z"));
    }

    @Test
    void missingSellerFeeQuoteBlocksPayoutBeforeProviderCall() {
        var transactions = transactions();
        assertThatThrownBy(() -> transactions.claim(org, payoutId))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        ex ->
                                assertThat(((ApiException) ex).code())
                                        .isEqualTo("PAYOUT_FEE_QUOTE_MISSING"));
        verify(store, never()).reserveCompanyFee(any(), any(), any());
        verify(store, never()).state(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void contradictoryNetQuoteBlocksPayout() {
        var transactions = transactions();
        var valid = approvedQuote();
        var changed =
                new MerchantPayoutFeeQuote(
                        valid.id(),
                        valid.organizationId(),
                        valid.campaignId(),
                        null,
                        valid.destinationId(),
                        valid.method(),
                        valid.phoneE164(),
                        valid.provider(),
                        valid.providerAccountReference(),
                        valid.grossAmount(),
                        valid.providerFee(),
                        new BigDecimal("981"),
                        valid.expiresAt());
        when(store.payoutFeeQuote(payoutId)).thenReturn(changed);
        assertThatThrownBy(() -> transactions.claim(org, payoutId))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        ex ->
                                assertThat(((ApiException) ex).code())
                                        .isEqualTo("PAYOUT_FEE_QUOTE_MISMATCH"));
        verify(store, never()).state(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void insufficientCampaignFundsBlockClaimEvenWithAcceptedSellerQuote() {
        var transactions = transactions();
        when(campaignPosition.eligibleBalance()).thenReturn(new BigDecimal("999"));
        when(store.payoutFeeQuote(payoutId)).thenReturn(approvedQuote());

        assertThatThrownBy(() -> transactions.claim(org, payoutId))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        ex ->
                                assertThat(((ApiException) ex).code())
                                        .isEqualTo("MERCHANT_FUNDS_NOT_AVAILABLE"));
        verify(store, never()).state(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void approvedSellerQuoteClaimsWithoutAnyCompanyFeeReservation() {
        var transactions = transactions();
        when(store.payoutFeeQuote(payoutId)).thenReturn(approvedQuote());
        assertThat(transactions.claim(org, payoutId)).isTrue();
        verify(store, never()).reserveCompanyFee(any(), any(), any());
        verify(store)
                .state(
                        eq(payoutId),
                        eq("PENDING"),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(true),
                        eq("PAYOUT_INITIATION_IN_PROGRESS"));
    }
}
