package sn.groupia.payment.api.event;

import java.time.Instant;
import java.util.UUID;
import sn.groupia.payment.api.OutgoingPayoutOperations.Kind;

public record OutgoingPayoutEvidenceReceived(
        UUID eventId,
        Instant occurredAt,
        UUID receiptId,
        Kind operationType,
        UUID operationId,
        UUID organizationId) {}
