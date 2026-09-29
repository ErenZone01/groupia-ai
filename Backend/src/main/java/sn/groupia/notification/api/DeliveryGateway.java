package sn.groupia.notification.api;
import java.util.UUID;
import sn.groupia.notification.api.NotificationContentSource.LinkContent;
/** Use the internal delivery UUID as an idempotency key only when supported by the provider. */
public interface DeliveryGateway {
    DeliveryResult sendOtp(UUID challengeId, String phone, String code);
    DeliveryResult sendLink(UUID deliveryId, NotificationType type, LinkContent content);
}
