package sn.groupia.notification.infrastructure;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import sn.groupia.notification.api.*;
/** No external provider is connected by this foundation. Never silently mark a delivery sent. */
@Component @Profile("!customer-access-local | prod | production")
class UnavailableDeliveryGateway implements DeliveryGateway {
    public void sendOtp(UUID id,String phone,String code){throw new IllegalStateException("NOTIFICATION_PROVIDER_NOT_CONFIGURED");}
    public void sendLink(UUID id,NotificationType type,String phone,String url){throw new IllegalStateException("NOTIFICATION_PROVIDER_NOT_CONFIGURED");}
}
