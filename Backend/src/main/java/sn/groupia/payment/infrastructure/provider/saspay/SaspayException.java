package sn.groupia.payment.infrastructure.provider.saspay;

/** Never retains response bodies, URLs, credentials or a transport exception as a cause. */
final class SaspayException extends RuntimeException {
    SaspayException(String code) {
        super(code);
    }
}
