package sn.groupia.settlement.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import sn.groupia.settlement.domain.model.MerchantPayout;
import sn.groupia.settlement.domain.repository.MerchantFinanceStore;
import sn.groupia.settlement.domain.service.MerchantPayoutProvider;

class SaspayPayoutPilotPolicyTests {
    private final UUID pilotOrganization = UUID.randomUUID();
    private MerchantPayoutTransactions transactions;
    private MerchantFinanceStore store;

    @BeforeEach
    void setup() {
        store = mock(MerchantFinanceStore.class);
        var provider = mock(MerchantPayoutProvider.class);
        when(provider.name()).thenReturn("SASPAY");
        transactions =
                new MerchantPayoutTransactions(
                        store,
                        null,
                        null,
                        null,
                        null,
                        provider,
                        Clock.systemUTC(),
                        null,
                        true,
                        true);
    }

    private void configure(boolean enabled, String organization, String maximum) {
        ReflectionTestUtils.setField(transactions, "saspayPayoutPilotEnabled", enabled);
        ReflectionTestUtils.setField(transactions, "saspayPayoutPilotOrganizationId", organization);
        ReflectionTestUtils.setField(
                transactions, "saspayPayoutPilotMaxXof", new BigDecimal(maximum));
    }

    @Test
    void pilotIsLockedByDefaultAndOnlyTheConfiguredOrganizationMayWithdraw() {
        assertThatThrownBy(
                        () ->
                                transactions.requireSaspayPilot(
                                        pilotOrganization, BigDecimal.valueOf(1000)))
                .hasMessageContaining("Pilote SasPay non autorisé");
        configure(true, pilotOrganization.toString(), "1000");
        transactions.requireSaspayPilot(pilotOrganization, BigDecimal.valueOf(1000));
        assertThatThrownBy(
                        () ->
                                transactions.requireSaspayPilot(
                                        UUID.randomUUID(), BigDecimal.valueOf(1000)))
                .hasMessageContaining("Boutique non autorisée");
    }

    @Test
    void pilotNeverAllowsMoreThanOneThousandOrFractionalXof() {
        configure(true, pilotOrganization.toString(), "1000");
        for (var amount : List.of("0", "-1", "1000.01", "1001")) {
            assertThatThrownBy(
                            () ->
                                    transactions.requireSaspayPilot(
                                            pilotOrganization, new BigDecimal(amount)))
                    .hasMessageContaining("plafond pilote");
        }
        configure(true, pilotOrganization.toString(), "1001");
        assertThatThrownBy(
                        () ->
                                transactions.requireSaspayPilot(
                                        pilotOrganization, BigDecimal.valueOf(100)))
                .hasMessageContaining("Pilote SasPay non autorisé");
    }

    @Test
    void livePilotTargetsOnlyTheSpecifiedDirectOrderAndWave() {
        var order = UUID.randomUUID();
        ReflectionTestUtils.setField(transactions, "saspayLivePilotReleased", true);
        ReflectionTestUtils.setField(
                transactions, "saspayLivePilotDirectOrderId", order.toString());

        transactions.requireLivePilotTarget(order, "WAVE");
        transactions.requireLivePilotTarget(order, null);
        assertThatThrownBy(() -> transactions.requireLivePilotTarget(null, "WAVE"))
                .hasMessageContaining("réservé à une vente directe");
        assertThatThrownBy(() -> transactions.requireLivePilotTarget(UUID.randomUUID(), "WAVE"))
                .hasMessageContaining("réservé à une vente directe");
        assertThatThrownBy(() -> transactions.requireLivePilotTarget(order, "ORANGE_MONEY"))
                .hasMessageContaining("destination Wave");

        ReflectionTestUtils.setField(transactions, "saspayLivePilotReleased", false);
        transactions.requireLivePilotTarget(null, "ORANGE_MONEY");
    }

    @Test
    void standardModeDoesNotApplyTemporaryPilotLimitsButStillBlocksResolutionIncidents() {
        configure(false, UUID.randomUUID().toString(), "1000");
        ReflectionTestUtils.setField(transactions, "saspayGeneralPayoutsEnabled", true);
        ReflectionTestUtils.setField(transactions, "saspayLivePilotReleased", true);
        ReflectionTestUtils.setField(
                transactions, "saspayLivePilotDirectOrderId", UUID.randomUUID().toString());
        transactions.requireSaspayPilot(pilotOrganization, new BigDecimal("2500"));
        transactions.requireLivePilotTarget(UUID.randomUUID(), "ORANGE_MONEY");
        var previous = mock(MerchantPayout.class);
        when(previous.provider()).thenReturn("SASPAY");
        when(previous.reserved()).thenReturn(true);
        when(store.payouts(pilotOrganization)).thenReturn(List.of(previous));
        transactions.checkPilotUsage(pilotOrganization);
        when(store.hasResolutionIncident(pilotOrganization)).thenReturn(true);
        assertThatThrownBy(() -> transactions.checkPilotUsage(pilotOrganization))
                .hasMessageContaining("Preuve tardive contradictoire");
    }

    @Test
    void productionPayoutsUseConfigurablePerRequestAndUtcDailyBudgets() {
        ReflectionTestUtils.setField(transactions, "saspayGeneralPayoutsEnabled", true);
        ReflectionTestUtils.setField(
                transactions, "saspayPayoutGeneralMaxXof", new BigDecimal("3000"));
        ReflectionTestUtils.setField(
                transactions, "saspayPayoutGeneralDailyMaxXof", new BigDecimal("5000"));
        transactions.requireSaspayPilot(pilotOrganization, new BigDecimal("2000"));
        assertThatThrownBy(
                        () ->
                                transactions.requireSaspayPilot(
                                        pilotOrganization, new BigDecimal("3001")))
                .hasMessageContaining("limite de versement");

        var prior = mock(MerchantPayout.class);
        var previousId = UUID.randomUUID();
        when(prior.id()).thenReturn(previousId);
        when(prior.provider()).thenReturn("SASPAY");
        when(prior.requestedAt()).thenReturn(Clock.systemUTC().instant());
        when(prior.amount()).thenReturn(new BigDecimal("4000"));
        when(store.payouts(pilotOrganization)).thenReturn(List.of(prior));

        transactions.requireGeneralDailyAllowance(pilotOrganization, new BigDecimal("1000"), null);
        assertThatThrownBy(
                        () ->
                                transactions.requireGeneralDailyAllowance(
                                        pilotOrganization, new BigDecimal("1001"), null))
                .hasMessageContaining("Plafond journalier");
        // A payout already persisted in the ledger must not count twice at dispatch.
        transactions.requireGeneralDailyAllowance(
                pilotOrganization, new BigDecimal("1000"), previousId);
        ReflectionTestUtils.setField(
                transactions, "saspayPayoutGeneralDailyMaxXof", new BigDecimal("0"));
        assertThatThrownBy(
                        () ->
                                transactions.requireSaspayPilot(
                                        pilotOrganization, new BigDecimal("1000")))
                .hasMessageContaining("limite de versement");
    }

    @Test
    void pilotRefusesSecondIntentEvenIfFirstPayoutAlreadyExists() {
        configure(true, pilotOrganization.toString(), "1000");
        when(store.payouts(pilotOrganization)).thenReturn(List.of());
        transactions.checkPilotUsage(pilotOrganization);
        var previous = mock(MerchantPayout.class);
        when(previous.provider()).thenReturn("SASPAY");
        when(previous.reserved()).thenReturn(true);
        when(store.payouts(pilotOrganization)).thenReturn(List.of(previous));
        assertThatThrownBy(() -> transactions.checkPilotUsage(pilotOrganization))
                .hasMessageContaining("Un retrait SasPay pilote non résolu existe");
    }

    @Test
    void definitivelyResolvedHistoryDoesNotConsumePilotForever() {
        configure(true, pilotOrganization.toString(), "1000");
        for (String status : List.of("FAILED", "CANCELLED", "NON_EXECUTE", "SUCCEEDED")) {
            var previous = mock(MerchantPayout.class);
            when(previous.provider()).thenReturn("SASPAY");
            when(previous.status()).thenReturn(status);
            when(store.payouts(pilotOrganization)).thenReturn(List.of(previous));
            transactions.checkPilotUsage(pilotOrganization);
        }
        when(store.hasResolutionIncident(pilotOrganization)).thenReturn(true);
        assertThatThrownBy(() -> transactions.checkPilotUsage(pilotOrganization))
                .hasMessageContaining("Preuve tardive contradictoire");
    }
}
