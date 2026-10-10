package sn.groupia.payment.infrastructure.provider.bictorys;

/** Never carries an HTTP response body, headers, customer details or a nested client exception. */
final class BictorysException extends RuntimeException
        implements sn.groupia.payment.domain.service.WebhookParsingFailure {
    BictorysException(String code) {
        super(code);
        if (code != null
                && (code.startsWith("BICTORYS_HTTP_")
                        || code.equals("BICTORYS_TRANSPORT_UNCERTAIN")))
            io.micrometer.core.instrument.Metrics.counter(
                            "groupia.provider.failures", "provider", "bictorys")
                    .increment();
    }

    private enum ParsingCode {
        BICTORYS_INVALID_JSON,
        BICTORYS_UNKNOWN_ENVELOPE,
        BICTORYS_INCOMPLETE_EVIDENCE,
        BICTORYS_UNSUPPORTED_EVENT,
        BICTORYS_INVALID_TIMESTAMP,
        BICTORYS_INVALID_FIELD,
        BICTORYS_INVALID_IDENTIFIER
    }

    @Override
    public Enum<?> diagnosticCode() {
        try {
            return ParsingCode.valueOf(getMessage());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return null;
        }
    }
}
