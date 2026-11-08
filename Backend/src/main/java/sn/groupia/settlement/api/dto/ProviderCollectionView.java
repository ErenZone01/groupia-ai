package sn.groupia.settlement.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProviderCollectionView(
        UUID id,
        UUID paymentRequestId,
        String targetType,
        UUID targetId,
        String purpose,
        String provider,
        String accountReference,
        String currency,
        BigDecimal sellerPrincipal,
        BigDecimal platformFee,
        BigDecimal collectionFee,
        BigDecimal customerCharged,
        Instant confirmedAt,
        String financialStatus,
        String payoutStatus) {}
