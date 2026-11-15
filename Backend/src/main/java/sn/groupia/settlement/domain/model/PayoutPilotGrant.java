package sn.groupia.settlement.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PayoutPilotGrant(
        UUID id,
        UUID organizationId,
        UUID uncertainPayoutId,
        UUID directOrderId,
        UUID destinationId,
        String account,
        BigDecimal amount,
        Instant expiresAt) {}
