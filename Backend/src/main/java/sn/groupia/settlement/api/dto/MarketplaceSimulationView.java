package sn.groupia.settlement.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MarketplaceSimulationView(
        UUID id,
        UUID collectionId,
        String mode,
        String kind,
        String status,
        String sourceProvider,
        String accountReference,
        String currency,
        BigDecimal recipientNet,
        BigDecimal fee,
        BigDecimal simulatedCompanyFunding,
        String reference,
        Instant createdAt,
        Instant completedAt) {}
