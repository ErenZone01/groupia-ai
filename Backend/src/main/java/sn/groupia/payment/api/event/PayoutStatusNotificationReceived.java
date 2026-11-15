package sn.groupia.payment.api.event;

import java.time.Instant;
import java.util.UUID;

/** Authenticated notification only. Consumers must obtain independent financial evidence. */
public record PayoutStatusNotificationReceived(
        UUID eventId, Instant occurredAt, String provider, String account, UUID transactionId) {}
