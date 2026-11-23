package sn.groupia.payment.infrastructure.provider.saspay;

/** Never retains response bodies, URLs, credentials or a transport exception as a cause. */
final class SaspayException extends RuntimeException
        implements sn.groupia.payment.domain.service.WebhookParsingFailure {
    enum Diagnostic {
        SASPAY_LEGACY_REFERENCE_UNAVAILABLE,
        SASPAY_INVALID_JSON,
        SASPAY_INVALID_RESPONSE,
        SASPAY_INVALID_IDENTIFIER,
        SASPAY_INVALID_MONEY,
        SASPAY_UNSUPPORTED_EVENT,
        SASPAY_UNSUPPORTED_FLOW,
        SASPAY_INCONSISTENT_EVENT,
        SASPAY_FINANCIAL_MISMATCH,
        SASPAY_STATUS_UNKNOWN,
        SASPAY_TRANSACTION_MISMATCH,
        SASPAY_HOSTED_SESSION_MISMATCH,
        SASPAY_HOSTED_TRANSACTION_MISMATCH,
        SASPAY_HOSTED_TRANSACTION_NOT_VERIFIED,
        SASPAY_HOSTED_SESSION_STATUS_UNSUPPORTED,
        SASPAY_HOSTED_DUPLICATE_SESSIONS,
        SASPAY_HOSTED_RECOVERY_NOT_EXHAUSTIVE,
        SASPAY_HOSTED_SESSION_NOT_FOUND,
        SASPAY_HOSTED_SESSION_LIST_INVALID,
        SASPAY_RATE_LIMITED,
        SASPAY_TRANSPORT_UNCERTAIN,
        SASPAY_QUOTE_REQUIRED,
        SASPAY_QUOTE_MISMATCH,
        SASPAY_PROVIDER_READ_FAILED
    }

    @Override
    public Enum<?> diagnosticCode() {
        if (httpStatus == 429) return Diagnostic.SASPAY_RATE_LIMITED;
        try {
            return Diagnostic.valueOf(getMessage());
        } catch (IllegalArgumentException ex) {
            return Diagnostic.SASPAY_PROVIDER_READ_FAILED;
        }
    }

    private final int httpStatus;
    private final String providerCode;

    SaspayException(String code) {
        this(code, 0, "NONE");
    }

    SaspayException(String code, int httpStatus, String providerCode) {
        super(code);
        this.httpStatus = httpStatus;
        this.providerCode = providerCode;
    }

    int httpStatus() {
        return httpStatus;
    }

    String providerCode() {
        return providerCode;
    }
}
