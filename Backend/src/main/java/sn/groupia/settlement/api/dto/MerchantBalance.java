package sn.groupia.settlement.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record MerchantBalance(
        String currency,
        BigDecimal grossMerchantCollections,
        BigDecimal platformFees,
        BigDecimal platformFundedRefunds,
        BigDecimal merchantPayouts,
        BigDecimal collectedBalance,
        BigDecimal eligibleBalance,
        BigDecimal reservedForPayout,
        BigDecimal availableForPayout,
        BigDecimal deficit,
        boolean canManageSettlements,
        boolean canManageDestination,
        boolean providerAvailable,
        List<CampaignSettlement> campaigns,
        List<DirectSettlement> directOrders,
        String payoutFeePolicy) {
    public MerchantBalance(
            String currency,
            BigDecimal grossMerchantCollections,
            BigDecimal platformFees,
            BigDecimal platformFundedRefunds,
            BigDecimal merchantPayouts,
            BigDecimal collectedBalance,
            BigDecimal eligibleBalance,
            BigDecimal reservedForPayout,
            BigDecimal availableForPayout,
            BigDecimal deficit,
            boolean canManageSettlements,
            boolean canManageDestination,
            boolean providerAvailable,
            List<CampaignSettlement> campaigns,
            List<DirectSettlement> directOrders) {
        this(
                currency,
                grossMerchantCollections,
                platformFees,
                platformFundedRefunds,
                merchantPayouts,
                collectedBalance,
                eligibleBalance,
                reservedForPayout,
                availableForPayout,
                deficit,
                canManageSettlements,
                canManageDestination,
                providerAvailable,
                campaigns,
                directOrders,
                "EXTERNAL");
    }

    public MerchantBalance(
            String currency,
            BigDecimal grossMerchantCollections,
            BigDecimal platformFees,
            BigDecimal platformFundedRefunds,
            BigDecimal merchantPayouts,
            BigDecimal collectedBalance,
            BigDecimal eligibleBalance,
            BigDecimal reservedForPayout,
            BigDecimal availableForPayout,
            BigDecimal deficit,
            boolean canManageSettlements,
            boolean canManageDestination,
            boolean providerAvailable,
            List<CampaignSettlement> campaigns) {
        this(
                currency,
                grossMerchantCollections,
                platformFees,
                platformFundedRefunds,
                merchantPayouts,
                collectedBalance,
                eligibleBalance,
                reservedForPayout,
                availableForPayout,
                deficit,
                canManageSettlements,
                canManageDestination,
                providerAvailable,
                campaigns,
                List.of());
    }
}
