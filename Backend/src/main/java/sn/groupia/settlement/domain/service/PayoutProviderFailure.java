package sn.groupia.settlement.domain.service;

/** Technical diagnostics only. No response body, request, credentials or exception cause. */
public final class PayoutProviderFailure extends IllegalStateException {
    public enum Submission {
        NOT_ATTEMPTED,
        UNKNOWN,
        ALREADY_SUBMITTED
    }

    public enum Stage {
        INTENT("NONE", "LOCAL", Submission.NOT_ATTEMPTED),
        QUOTE("GET", "PRICING", Submission.NOT_ATTEMPTED),
        RATES("GET", "PRICING", Submission.NOT_ATTEMPTED),
        WALLETS("GET", "MERCHANT_BALANCES", Submission.NOT_ATTEMPTED),
        SERIALIZE("NONE", "PAYOUT_INITIALIZE", Submission.NOT_ATTEMPTED),
        INITIALIZE("POST", "PAYOUT_INITIALIZE", Submission.UNKNOWN),
        VERIFY("GET", "PAYOUT_VERIFY", Submission.ALREADY_SUBMITTED),
        DETAILS("GET", "TRANSACTION_DETAIL", Submission.ALREADY_SUBMITTED);

        public final String method;
        public final String endpoint;
        public final Submission submission;

        Stage(String method, String endpoint, Submission submission) {
            this.method = method;
            this.endpoint = endpoint;
            this.submission = submission;
        }
    }

    public enum Category {
        VALIDATION,
        AUTHENTICATION,
        AUTHORIZATION,
        CONFLICT,
        RATE_LIMIT,
        PROVIDER_REJECTION,
        SERVER_ERROR,
        CONTRACT,
        TRANSPORT,
        SERIALIZATION
    }

    // Closed vocabulary: even a malicious response cannot inject PII into a diagnostic.
    public enum Reason {
        HTTP_REJECTED,
        HTTP_SERVER_ERROR,
        UNEXPECTED_HTTP_STATUS,
        PROVIDER_REPORTED_FAILURE,
        INVALID_ENVELOPE,
        INVALID_JSON,
        INVALID_CONTENT_TYPE,
        RESPONSE_TOO_LARGE,
        TRANSPORT_UNCERTAIN,
        RESPONSE_READ_FAILED,
        SERIALIZATION_FAILED,
        LOCAL_VALIDATION_FAILED,
        NOT_AUTHORIZED,
        INTENT_INVALID,
        PHONE_INVALID,
        METHOD_UNSUPPORTED,
        NETWORK_NOT_APPROVED,
        LIVE_PILOT_TARGET_MISMATCH,
        FEE_QUOTE_MISSING,
        FEE_QUOTE_MISMATCH,
        PREFLIGHT_UNAVAILABLE,
        QUOTE_CHANGED,
        NETWORK_UNAVAILABLE,
        RATE_AMBIGUOUS,
        RATE_INVALID,
        FEE_MODE_UNKNOWN,
        FEE_PRECISION_UNCONFIRMED,
        NET_AMOUNT_NOT_POSITIVE,
        WALLET_UNVERIFIED,
        WALLET_FROZEN,
        WALLET_INSUFFICIENT,
        RATE_INCOMPLETE,
        IDENTIFIER_INVALID,
        STATUS_CONFLICT,
        FINANCIAL_MISMATCH,
        RECIPIENT_MISMATCH,
        NETWORK_EVIDENCE_MISSING,
        RECIPIENT_EVIDENCE_MISSING,
        INVALID_TIMESTAMP,
        EVIDENCE_MISMATCH,
        STATUS_UNKNOWN,
        AMOUNT_INVALID
    }

    public enum ProviderCode {
        NONE,
        UNRECOGNIZED,
        ip_not_whitelisted,
        payout_not_enabled,
        currency_country_mismatch,
        invalid_country,
        invalid_customer,
        invalid_method,
        no_route_available,
        wallet_frozen,
        insufficient_balance,
        merchant_suspended,
        not_found
    }

    private final Stage stage;
    private final int httpStatus;
    private final Category category;
    private final ProviderCode providerCode;

    public PayoutProviderFailure(
            Stage stage,
            int httpStatus,
            Category category,
            Reason reason,
            ProviderCode providerCode) {
        super("SASPAY_PAYOUT_" + reason.name());
        this.stage = stage;
        this.httpStatus = httpStatus;
        this.category = category;
        this.providerCode = providerCode;
    }

    public Stage stage() {
        return stage;
    }

    /** Zero means no response status was obtained. */
    public int httpStatus() {
        return httpStatus;
    }

    public Category category() {
        return category;
    }

    public ProviderCode providerCode() {
        return providerCode;
    }

    public Submission submission() {
        return stage.submission;
    }
}
