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

    /** Allows limiting a live provider to a designated pilot organization. */
    default boolean canQuoteForOrganization(java.util.UUID organizationId) {
        return true;
    }

    /** Static adapter metadata; financial validation remains mandatory for either policy. */
    default WebhookSuccessEvidence webhookSuccessEvidence() {
        return WebhookSuccessEvidence.SERVER_VERIFICATION;
    }

    /**
     * Safe, local configuration probe before claiming a durable payment attempt. Must never contact
     * the PSP, inspect an order, or initiate any transaction. Returns a fixed internal code, never
     * a credential, URL or customer value.
     */
    default String initiationConfigurationIssue() {
        return null;
    }

    ProviderObservation initiate(ProviderInstruction instruction);

    ProviderObservation verify(ProviderInstruction instruction);

    boolean verifyAuthenticity(byte[] payload, Map<String, String> headers);

    NormalizedWebhook parse(byte[] payload);
}
