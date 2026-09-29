package sn.groupia.notification.infrastructure.whatsapp;
/** Never retain the transport exception, response body or request as a cause. */
public final class WhatsAppDeliveryException extends RuntimeException {
    WhatsAppDeliveryException() { super("WHATSAPP_DELIVERY_FAILED"); }
}
