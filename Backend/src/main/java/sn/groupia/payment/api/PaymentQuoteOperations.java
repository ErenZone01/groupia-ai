package sn.groupia.payment.api;

import java.util.UUID;
import sn.groupia.payment.api.dto.PaymentFeeQuoteView;

/** Caller must authorize access to the obligation before invoking this interface. */
public interface PaymentQuoteOperations {
    java.util.List<PaymentFeeQuoteView> quotes(UUID organizationId, UUID requestId);
}
