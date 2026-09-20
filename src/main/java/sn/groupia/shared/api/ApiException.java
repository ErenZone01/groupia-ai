package sn.groupia.shared.api;

/** An application failure with a stable, safe public error contract. */
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final java.util.Map<String, Object> details;

    public ApiException(int status, String code, String message) {
        this(status, code, message, java.util.Map.of());
    }
    public ApiException(int status, String code, String message, java.util.Map<String, Object> details) {
        super(message);
        this.details = java.util.Map.copyOf(details);
        this.status = status;
        this.code = code;
    }

    public java.util.Map<String, Object> details() { return details; }
    public int status() { return status; }
    public String code() { return code; }
}
