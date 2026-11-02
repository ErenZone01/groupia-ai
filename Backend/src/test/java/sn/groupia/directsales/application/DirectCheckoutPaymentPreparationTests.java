package sn.groupia.directsales.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import sn.groupia.shared.api.ApiException;

class DirectCheckoutPaymentPreparationTests {
    private final UUID organization = UUID.randomUUID();
    private final UUID directOrder = UUID.randomUUID();
    private final DirectPaymentObligations obligations = mock(DirectPaymentObligations.class);

    @Test
    void featureIsDisabledByDefaultWithoutAnyFinancialSideEffect() {
        var handoff = new DirectCheckoutPaymentPreparation(obligations, false);

        handoff.afterCreated(organization, directOrder, "PICKUP", false, 8800L);
        handoff.afterCreated(organization, directOrder, "DELIVERY", true, 10300L);

        verifyNoInteractions(obligations);
    }

    @Test
    void newAcceptedPickupAndVerifiedMapDeliveryCreateExactlyOneObligationEach() {
        var handoff = new DirectCheckoutPaymentPreparation(obligations, true);

        handoff.afterCreated(organization, directOrder, "PICKUP", false, 8800L);
        handoff.afterCreated(organization, directOrder, "DELIVERY", true, 10300L);

        verify(obligations, times(2)).prepare(organization, directOrder);
        verifyNoMoreInteractions(obligations);
    }

    @Test
    void legacyUnquotedManualDeliveryAndUnknownFulfillmentCannotPrepare() {
        var handoff = new DirectCheckoutPaymentPreparation(obligations, true);

        handoff.afterCreated(organization, directOrder, "PICKUP", false, null);
        handoff.afterCreated(organization, directOrder, "DELIVERY", true, null);
        handoff.afterCreated(organization, directOrder, "DELIVERY", false, 10300L);
        handoff.afterCreated(organization, directOrder, "UNKNOWN", true, 10300L);

        verifyNoInteractions(obligations);
    }

    @Test
    void preparationFailurePropagatesSoCheckoutTransactionCanRollBack() {
        var handoff = new DirectCheckoutPaymentPreparation(obligations, true);
        doThrow(new ApiException(409, "DIRECT_PAYMENT_ORDER_INELIGIBLE", "Invalid snapshot"))
                .when(obligations)
                .prepare(organization, directOrder);

        assertThatThrownBy(
                        () ->
                                handoff.afterCreated(
                                        organization, directOrder, "PICKUP", false, 8800L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid snapshot");
        verify(obligations).prepare(organization, directOrder);
    }
}
