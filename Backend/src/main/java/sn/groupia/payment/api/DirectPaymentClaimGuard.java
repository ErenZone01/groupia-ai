package sn.groupia.payment.api;

import java.math.BigDecimal;
import java.util.UUID;
import sn.groupia.payment.api.dto.PaymentCheckoutContext;

/**
 * Cross-module pre-claim authority for direct sales. Implementations MUST lock the storefront and
 * direct order before the payment module locks its financial obligation; the returned context must
 * come from server-owned order snapshots, never public input. Called inside the claim transaction.
 */
public interface DirectPaymentClaimGuard {
    PaymentCheckoutContext authorize(
            UUID organizationId,
            UUID directOrderId,
            UUID paymentRequestId,
            BigDecimal amount,
            PaymentCheckoutContext checkoutContext,
            String recoveryTokenHash);
}
