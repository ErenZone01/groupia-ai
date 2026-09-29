package sn.groupia.notification.infrastructure;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import sn.groupia.notification.api.*;
/** No configured transport. Never silently mark a delivery sent. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="groupia.notification.whatsapp.enabled",havingValue="false",matchIfMissing=true)
@Component @Profile("!customer-access-local | prod | production")
class UnavailableDeliveryGateway implements DeliveryGateway {
    public DeliveryResult sendOtp(UUID id,String phone,String code){throw new IllegalStateException("NOTIFICATION_PROVIDER_NOT_CONFIGURED");}
    public DeliveryResult sendLink(UUID id,NotificationType type,NotificationContentSource.LinkContent content){throw new IllegalStateException("NOTIFICATION_PROVIDER_NOT_CONFIGURED");}
}
