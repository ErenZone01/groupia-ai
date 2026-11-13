package sn.groupia.settlement.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sn.groupia.grouping.api.MerchantCampaignFacts;
import sn.groupia.organization.api.OrganizationAccess;
import sn.groupia.payment.api.OutgoingPayoutOperations;
import sn.groupia.settlement.domain.model.MerchantPayout;
import sn.groupia.settlement.domain.repository.MerchantFinanceStore;
import sn.groupia.settlement.domain.service.MerchantPayoutProvider;
import sn.groupia.shared.api.ApiException;

class MerchantPayoutFeeBudgetTests {
    private final MerchantFinanceStore store = mock(MerchantFinanceStore.class);
    private final MerchantPayoutProvider provider = mock(MerchantPayoutProvider.class);
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
        return new MerchantPayoutTransactions(
                store,
                mock(MerchantBalances.class),
                mock(DirectMerchantBalances.class),
                mock(MerchantCampaignFacts.class),
                mock(OrganizationAccess.class),
                provider,
                Clock.systemUTC(),
                mock(OutgoingPayoutOperations.class),
                true,
                false);
    }

    @Test
    void missingApprovedCeilingDoesNotClaimOrSendPayout() {
        var transactions = transactions();
        assertThatThrownBy(() -> transactions.claim(org, payoutId))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        ex ->
                                assertThat(((ApiException) ex).code())
                                        .isEqualTo("SASPAY_COMPANY_FEE_CEILING_UNCONFIRMED"));
        verify(store, never()).reserveCompanyFee(any(), any(), any());
        verify(store, never())
                .state(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void noCompanyFundingDoesNotClaimOrSendPayout() {
        var transactions = transactions();
        BigDecimal ceiling = new BigDecimal("80");
        when(provider.feeReservationCeiling(payout)).thenReturn(ceiling);
        doThrow(new IllegalStateException("SASPAY_COMPANY_FEE_BUDGET_UNFUNDED"))
                .when(store)
                .reserveCompanyFee(account, payoutId, ceiling);
        assertThatThrownBy(() -> transactions.claim(org, payoutId))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        ex ->
                                assertThat(((ApiException) ex).code())
                                        .isEqualTo("SASPAY_COMPANY_FEE_BUDGET_UNAVAILABLE"));
        verify(store, never())
                .state(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void fundedCompanyFeeIsReservedBeforePayoutCanBeClaimed() {
        var transactions = transactions();
        BigDecimal ceiling = new BigDecimal("80");
        when(provider.feeReservationCeiling(payout)).thenReturn(ceiling);
        assertThat(transactions.claim(org, payoutId)).isTrue();
        var order = inOrder(store);
        order.verify(store).reserveCompanyFee(account, payoutId, ceiling);
        order.verify(store)
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
