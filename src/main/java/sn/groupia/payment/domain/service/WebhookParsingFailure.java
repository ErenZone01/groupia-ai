package sn.groupia.payment.domain.service;

/** Implemented by adapter exceptions. Only compile-time enum names may cross this diagnostic boundary. */
public interface WebhookParsingFailure {
    String FALLBACK_CODE = "WEBHOOK_PARSE_FAILED";
    Enum<?> diagnosticCode();

    static String safeCode(RuntimeException failure) {
        if (failure instanceof WebhookParsingFailure classified) {
            try {
                var code = classified.diagnosticCode();
                if (code != null && code.name().matches("[A-Z][A-Z0-9_]{0,63}")) { return code.name(); }
            } catch (RuntimeException ignored) { /* Never expose an exception message or cause. */ }
        }
        return FALLBACK_CODE;
    }
}
