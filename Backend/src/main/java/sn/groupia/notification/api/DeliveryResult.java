package sn.groupia.notification.api;
/** Acceptance by the transport is not delivery or reading by the recipient. */
public record DeliveryResult(String provider, String providerMessageId) {
    @Override public String toString() { return "DeliveryResult[redacted]"; }
}
