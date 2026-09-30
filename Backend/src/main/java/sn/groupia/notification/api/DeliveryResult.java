package sn.groupia.notification.api;
/** Acceptance is not delivery/reading. DISABLED with a null id means intentionally not sent. */
public record DeliveryResult(String provider, String providerMessageId) {
    @Override public String toString() { return "DeliveryResult[redacted]"; }
}
