package sn.groupia.notification.api;
import java.util.UUID;
/** Provider adapter. The UUID must be used as the delivery idempotency key when supported. */
public interface DeliveryGateway {
    void sendOtp(UUID challengeId,String phone,String code);
    void sendLink(UUID deliveryId,NotificationType type,String phone,String url);
}
