package sn.groupia.settlement.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import sn.groupia.organization.api.OrganizationAccess;
import sn.groupia.settlement.api.dto.DirectSettlement;
import sn.groupia.settlement.domain.model.*;
import sn.groupia.settlement.domain.repository.MerchantFinanceStore;
import sn.groupia.settlement.domain.service.MerchantPayoutProvider;

class SaspayPayoutRecoveryPolicyTests {
    private final UUID org = UUID.randomUUID();
    private final UUID payoutId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID destinationId = UUID.randomUUID();
    private final UUID idempotencyKey = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final String account = UUID.randomUUID().toString();
    private MerchantPayoutTransactions transactions;
    private MerchantFinanceStore store;
    private MerchantPayoutProvider provider;
    private OrganizationAccess access;
    private DirectMerchantBalances direct;

    private MerchantPayout payout(String status, String reason) {
        return new MerchantPayout(
                payoutId,
                org,
                null,
                new BigDecimal("1000.00"),
                "XOF",
                status,
                destinationId,
                "WAVE",
                "+221771234567",
                "Awa Test",
                "SASPAY",
                account,
                "MERCHANT_PAYOUT:" + org + ":" + UUID.randomUUID(),
                idempotencyKey,
                null,
                actor,
                Instant.parse("2026-11-14T06:23:43Z"),
                null,
                null,
                true,
                reason,
                0,
                orderId);
    }

    private MerchantPayoutFeeQuote quote() {
        return new MerchantPayoutFeeQuote(
                UUID.randomUUID(),
                org,
                null,
                orderId,
                destinationId,
                "WAVE",
                "+221771234567",
                "SASPAY",
                account,
                new BigDecimal("1000.00"),
                new BigDecimal("37.00"),
                new BigDecimal("963.00"),
                Instant.parse("2026-11-14T06:25:43Z"));
    }

    private MerchantPayoutDestination destination() {
        return new MerchantPayoutDestination(
                destinationId,
                org,
                "WAVE",
                "+221771234567",
                "Awa Test",
                "ACTIVE",
                Instant.now(),
                Instant.now(),
                actor,
                actor,
                0);
    }

    @BeforeEach
    void setup() {
        store = mock(MerchantFinanceStore.class);
        provider = mock(MerchantPayoutProvider.class);
        access = mock(OrganizationAccess.class);
        direct = mock(DirectMerchantBalances.class);
        when(provider.name()).thenReturn("SASPAY");
        when(provider.account()).thenReturn(account);
        when(provider.enabled()).thenReturn(true);
        when(access.requireActorPermission(any(), any())).thenReturn(actor);
        transactions =
                new MerchantPayoutTransactions(
                        store,
                        null,
                        direct,
                        null,
                        access,
                        provider,
                        Clock.systemUTC(),
                        null,
                        true,
                        true);
        ReflectionTestUtils.setField(transactions, "saspayPayoutPilotEnabled", true);
        ReflectionTestUtils.setField(transactions, "saspayPayoutPilotOrganizationId", org.toString());
        ReflectionTestUtils.setField(
                transactions, "saspayPayoutPilotMaxXof", new BigDecimal("1000"));
        ReflectionTestUtils.setField(transactions, "saspayLivePilotReleased", true);
        ReflectionTestUtils.setField(
                transactions, "saspayLivePilotDirectOrderId", orderId.toString());
        ReflectionTestUtils.setField(
                transactions, "saspayPayoutRecoveryId", payoutId.toString());
    }

    private void enableRecovery() {
        ReflectionTestUtils.setField(transactions, "saspayPayoutRecoveryEnabled", true);
    }

    private void validFacts(MerchantPayout payout) {
        when(store.payout(org, payoutId)).thenReturn(payout);
        when(store.payouts(org)).thenReturn(List.of(payout));
        when(store.destination(org)).thenReturn(Optional.of(destination()));
        when(store.payoutFeeQuote(payoutId)).thenReturn(quote());
        when(direct.calculate(org))
                .thenReturn(
                        List.of(
                                new DirectSettlement(
                                        orderId,
                                        "DIR-TEST",
                                        "COMPLETED",
                                        "XOF",
                                        "SASPAY",
                                        account,
                                        new BigDecimal("1000"),
                                        BigDecimal.ZERO,
                                        new BigDecimal("1000"),
                                        BigDecimal.ZERO,
                                        new BigDecimal("1000"),
                                        BigDecimal.ZERO,
                                        BigDecimal.ZERO)));
    }

    @Test
    void recoveryIsLockedByDefaultAndRequiresExplicitConfirmation() {
        var original = payout("REVIEW_REQUIRED", "PAYOUT_REFERENCE_UNAVAILABLE");
        validFacts(original);
        assertThatThrownBy(() -> transactions.claimRecovery(org, payoutId, true))
                .hasMessageContaining("Récupération pilote désactivée");
        verify(store, never()).lockOrganization(any());
        enableRecovery();
        assertThatThrownBy(() -> transactions.claimRecovery(org, payoutId, false))
                .hasMessageContaining("Confirmez la récupération");
        verify(store, never()).state(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void recoveryClaimsOnlyOriginalIntentAndCannotBeClaimedTwice() {
        var original = payout("REVIEW_REQUIRED", "PAYOUT_REFERENCE_UNAVAILABLE");
        validFacts(original);
        enableRecovery();
        var claimed = transactions.claimRecovery(org, payoutId, true);
        assertThat(claimed.id()).isEqualTo(payoutId);
        assertThat(claimed.providerIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(claimed.destinationId()).isEqualTo(destinationId);
        verify(store, times(1))
                .state(
                        eq(payoutId),
                        eq("PENDING"),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(true),
                        eq("PAYOUT_MANUAL_RECOVERY_IN_PROGRESS"));
        when(store.payout(org, payoutId))
                .thenReturn(payout("PENDING", "PAYOUT_MANUAL_RECOVERY_IN_PROGRESS"));
        assertThatThrownBy(() -> transactions.claimRecovery(org, payoutId, true))
                .hasMessageContaining("Retrait non récupérable");
        verify(store, times(1)).state(any(), any(), any(), any(), any(), anyBoolean(), any());
        verify(store, never()).create(any());
    }

    @Test
    void rejectsDifferentPayoutIdAndUnsafeHistoricStatus() {
        var original = payout("REVIEW_REQUIRED", "PAYOUT_REFERENCE_UNAVAILABLE");
        validFacts(original);
        enableRecovery();
        assertThatThrownBy(() -> transactions.claimRecovery(org, UUID.randomUUID(), true))
                .hasMessageContaining("Récupération pilote désactivée");
        when(store.payout(org, payoutId))
                .thenReturn(payout("REVIEW_REQUIRED", "PAYOUT_PROVIDER_UNCERTAIN"));
        assertThatThrownBy(() -> transactions.claimRecovery(org, payoutId, true))
                .hasMessageContaining("Retrait non récupérable");
        verify(store, never()).state(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void refusesChangedBeneficiaryOrInsufficientReservedBalance() {
        var original = payout("REVIEW_REQUIRED", "PAYOUT_REFERENCE_UNAVAILABLE");
        validFacts(original);
        enableRecovery();
        when(store.destination(org))
                .thenReturn(Optional.of(new MerchantPayoutDestination(
                        destinationId, org, "WAVE", "+221779999999", "Awa Test",
                        "ACTIVE", Instant.now(), Instant.now(), actor, actor, 0)));
        assertThatThrownBy(() -> transactions.claimRecovery(org, payoutId, true))
                .hasMessageContaining("destination a changé");
        when(store.destination(org)).thenReturn(Optional.of(destination()));
        when(direct.calculate(org))
                .thenReturn(List.of(new DirectSettlement(
                        orderId, "DIR-TEST", "COMPLETED", "XOF", "SASPAY", account,
                        new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("1000"),
                        BigDecimal.ZERO, new BigDecimal("900"),
                        new BigDecimal("100"), BigDecimal.ZERO)));
        assertThatThrownBy(() -> transactions.claimRecovery(org, payoutId, true))
                .hasMessageContaining("Fonds réservés non vérifiés");
        verify(store, never()).state(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void uncertainRecoveryKeepsReservationAndCannotRevertToReferenceUnavailable() {
        var original = payout("PENDING", "PAYOUT_MANUAL_RECOVERY_IN_PROGRESS");
        when(store.payout(org, payoutId)).thenReturn(original);
        transactions.recoveryUncertain(org, payoutId);
        verify(store)
                .state(
                        eq(payoutId), eq("REVIEW_REQUIRED"), isNull(), isNull(), isNull(),
                        eq(true), eq("PAYOUT_MANUAL_RECOVERY_UNCERTAIN"));
        when(store.payout(org, payoutId))
                .thenReturn(payout("REVIEW_REQUIRED", "PAYOUT_MANUAL_RECOVERY_UNCERTAIN"));
        transactions.requiresOperator(org, payoutId);
        verify(store, times(1)).state(any(), any(), any(), any(), any(), anyBoolean(), any());
    }
}
