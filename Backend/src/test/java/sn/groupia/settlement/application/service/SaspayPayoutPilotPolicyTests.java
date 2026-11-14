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
        assertThatThrownBy(
                        () -> transactions.requireLivePilotTarget(UUID.randomUUID(), "WAVE"))
                .hasMessageContaining("réservé à une vente directe");
        assertThatThrownBy(() -> transactions.requireLivePilotTarget(order, "ORANGE_MONEY"))
                .hasMessageContaining("destination Wave");

        ReflectionTestUtils.setField(transactions, "saspayLivePilotReleased", false);
        transactions.requireLivePilotTarget(null, "ORANGE_MONEY");
    }

    @Test
    void pilotRefusesSecondIntentEvenIfFirstPayoutAlreadyExists() {
        configure(true, pilotOrganization.toString(), "1000");
        when(store.payouts(pilotOrganization)).thenReturn(List.of());
        transactions.checkPilotUsage(pilotOrganization);
        var previous = mock(MerchantPayout.class);
        when(previous.provider()).thenReturn("SASPAY");
        when(store.payouts(pilotOrganization)).thenReturn(List.of(previous));
        assertThatThrownBy(() -> transactions.checkPilotUsage(pilotOrganization))
                .hasMessageContaining("Un retrait SasPay pilote existe déjà");
    }
}
