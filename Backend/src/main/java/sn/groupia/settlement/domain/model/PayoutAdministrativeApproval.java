package sn.groupia.settlement.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A reviewed, server-pinned authorization. Never supplied as evidence by the HTTP caller. */
public record PayoutAdministrativeApproval(
        String reference,
        String action,
        UUID organizationId,
        UUID payoutId,
        long payoutVersion,
        UUID operatorId,
        String providerAccount,
        String originalKeySha256,
        String businessReferenceSha256,
        BigDecimal amount,
        String currency,
        String evidenceType,
        String evidenceReference,
        String evidenceSha256,
        boolean allAttemptsCovered,
        boolean definitiveNonExecution,
        boolean futureExecutionImpossible,
        Instant reviewedAt,
        Instant expiresAt,
        UUID directOrderId,
        UUID destinationId) {}
