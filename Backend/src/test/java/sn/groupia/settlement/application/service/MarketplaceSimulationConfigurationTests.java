package sn.groupia.settlement.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import sn.groupia.organization.api.OrganizationAccess;
import sn.groupia.payment.api.PaymentOperations;
import sn.groupia.shared.api.ApiException;

class MarketplaceSimulationConfigurationTests {
    @Test
    void disabledConfigurationCannotReserveOrCallDatabase() {
        var jdbc = mock(JdbcTemplate.class);
        var service =
                new MarketplaceSimulationTransactions(
                        jdbc,
                        mock(OrganizationAccess.class),
                        mock(PaymentOperations.class),
                        Clock.systemUTC(),
                        false,
                        new BigDecimal("200"),
                        new BigDecimal("100000"));
        assertThatThrownBy(
                        () ->
                                service.reserve(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        new MarketplaceSimulationTransactions.SimulationCommand(
                                                "PAYOUT", "quote", true)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("désactivées");
        verifyNoInteractions(jdbc);
    }

    @Test
    void fractionalAndNegativeFeesFailConfiguration() {
        for (var fee : new BigDecimal[] {new BigDecimal("0.1"), new BigDecimal("-1")})
            assertThatThrownBy(
                            () ->
                                    new MarketplaceSimulationTransactions(
                                            mock(JdbcTemplate.class),
                                            mock(OrganizationAccess.class),
                                            mock(PaymentOperations.class),
                                            Clock.systemUTC(),
                                            true,
                                            fee,
                                            BigDecimal.ZERO))
                    .isInstanceOf(RuntimeException.class);
    }
}
