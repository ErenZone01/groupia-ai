package sn.groupia.payment.domain.service;

import java.util.Map;

/**
 * Implemented only by infrastructure adapters; no database transaction may surround these calls.
 */
public interface PaymentProvider {
    String name();

    default java.util.List<ProviderFee> quotes(java.math.BigDecimal amount, String currency) {
        return java.util.List.of();
    }

    default boolean requiresFeeQuote() {
        return false;
    }

    default ProviderFee quote(java.math.BigDecimal amount, String currency, String network) {
        throw new UnsupportedOperationException("Provider does not support fee quotes");
    }

    /** Server-selected merchant account, never trusted from public input. */
    String accountReference();

    /** Static adapter metadata; financial validation remains mandatory for either policy. */
    default WebhookSuccessEvidence webhookSuccessEvidence() {
        return WebhookSuccessEvidence.SERVER_VERIFICATION;
    }

    ProviderObservation initiate(ProviderInstruction instruction);

    ProviderObservation verify(ProviderInstruction instruction);

    boolean verifyAuthenticity(byte[] payload, Map<String, String> headers);

    NormalizedWebhook parse(byte[] payload);
}
