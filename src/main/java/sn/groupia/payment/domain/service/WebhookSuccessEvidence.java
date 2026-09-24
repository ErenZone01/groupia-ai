package sn.groupia.payment.domain.service;

/** Trusted adapter policy, never selected by webhook content or public request parameters. */
public enum WebhookSuccessEvidence {
    SERVER_VERIFICATION,
    AUTHENTICATED_WEBHOOK
}
