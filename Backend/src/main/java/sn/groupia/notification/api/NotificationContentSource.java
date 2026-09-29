package sn.groupia.notification.api;
import java.util.*;
/** Notification-owned SPI; content is materialized only while delivering, never serialized in events. */
public interface NotificationContentSource {
    Optional<LinkContent> link(UUID linkId);
    record LinkContent(String phone,String orderNumber,String url) {
        @Override public String toString(){return "LinkContent[redacted]";}
    }
}
