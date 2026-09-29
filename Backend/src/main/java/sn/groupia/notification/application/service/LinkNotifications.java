package sn.groupia.notification.application.service;
import org.springframework.stereotype.Component;
import org.springframework.modulith.events.ApplicationModuleListener;
import sn.groupia.notification.api.*;
import sn.groupia.notification.api.event.LinkNotificationRequested;
@Component
public class LinkNotifications {
    private final NotificationContentSource content;private final DeliveryGateway gateway;
    LinkNotifications(NotificationContentSource content,DeliveryGateway gateway){this.content=content;this.gateway=gateway;}
    @ApplicationModuleListener
    public void on(LinkNotificationRequested event) {
        content.link(event.linkId()).ifPresent(link->{
            try{gateway.sendLink(event.eventId(),event.type(),link.phone(),link.url());}
            catch(RuntimeException ex){throw new IllegalStateException("NOTIFICATION_DELIVERY_UNAVAILABLE");}
        });
    }
}
