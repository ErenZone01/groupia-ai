package sn.groupia.settlement.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** One direct-sale financial position; amounts are scoped to the verified source account. */
public record DirectSettlement(
        UUID directOrderId,
        String orderNumber,
        String status,
        String currency,
        String provider,
        String accountReference,
        BigDecimal grossMerchantCollections,
        BigDecimal eligibleBalance,
        BigDecimal merchantPayouts,
        BigDecimal reservedForPayout,
        BigDecimal availableForPayout,
        BigDecimal deficit) {}
