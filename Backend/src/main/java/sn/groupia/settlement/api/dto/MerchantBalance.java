package sn.groupia.settlement.api.dto;
import java.math.BigDecimal;
import java.util.List;
public record MerchantBalance(String currency,BigDecimal grossMerchantCollections,BigDecimal platformFees,
    BigDecimal platformFundedRefunds,BigDecimal merchantPayouts,BigDecimal collectedBalance,
    BigDecimal eligibleBalance,BigDecimal reservedForPayout,BigDecimal availableForPayout,BigDecimal deficit,
    boolean canManageSettlements,boolean canManageDestination,boolean providerAvailable,List<CampaignSettlement> campaigns) {}
