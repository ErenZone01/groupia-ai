package sn.groupia.payment.infrastructure.provider.saspay;

/** Never retains response bodies, URLs, credentials or a transport exception as a cause. */
final class SaspayException extends RuntimeException {
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
