package sn.groupia.notification.api.event;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.notification.api.NotificationType;
public record LinkNotificationRequested(UUID eventId,Instant occurredAt,UUID linkId,NotificationType type) {}
