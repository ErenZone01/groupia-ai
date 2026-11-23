package sn.groupia.settlement.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sn.groupia.organization.api.*;
import sn.groupia.settlement.domain.repository.MerchantFinanceStore;
import sn.groupia.settlement.domain.service.MerchantPayoutProvider;
import sn.groupia.shared.api.ApiException;

class SaspayPayoutRecoveryPolicyTests {
    @Test
    void uncertainPayoutCannotBeResubmittedEvenWithExplicitConfirmation() {
        var store = mock(MerchantFinanceStore.class);
        var provider = mock(MerchantPayoutProvider.class);
        var access = mock(OrganizationAccess.class);
        var transactions =
                new MerchantPayoutTransactions(
                        store,
                        null,
                        null,
                        null,
                        access,
                        provider,
                        Clock.systemUTC(),
                        null,
                        true,
                        true);
        var service = new MerchantSettlementService(transactions, store, null, provider, access);
        UUID org = UUID.randomUUID();
        assertThatThrownBy(() -> service.recoverPilot(org, UUID.randomUUID(), true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("réémission");
        verify(access).requireActorPermission(org, OrganizationPermission.EXECUTE_PAYOUTS);
        verify(access)
                .requireActorPermission(org, OrganizationPermission.MANAGE_PAYOUT_DESTINATION);
        // In particular: no POST, no state rewrite, no reservation release.
        verifyNoInteractions(store, provider);
    }

    @Test
    void retiredRecoveryStillRequiresAuthorizationBeforeAnyFinancialRead() {
        var access = mock(OrganizationAccess.class);
        var store = mock(MerchantFinanceStore.class);
        var provider = mock(MerchantPayoutProvider.class);
        UUID org = UUID.randomUUID();
        when(access.requireActorPermission(org, OrganizationPermission.EXECUTE_PAYOUTS))
                .thenThrow(new ApiException(403, "ORGANIZATION_ACCESS_DENIED", "Accès interdit"));
        var transactions =
                new MerchantPayoutTransactions(
                        store,
                        null,
                        null,
                        null,
                        access,
                        provider,
                        Clock.systemUTC(),
                        null,
                        true,
                        true);
        assertThatThrownBy(() -> transactions.claimRecovery(org, UUID.randomUUID(), true))
                .hasMessage("Accès interdit");
        verifyNoInteractions(store, provider);
    }
}
