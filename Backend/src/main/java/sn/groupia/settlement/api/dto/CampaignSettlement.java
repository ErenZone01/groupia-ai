package sn.groupia.settlement.api.dto;
import java.math.BigDecimal;
import java.util.UUID;
public record CampaignSettlement(UUID campaignId,String title,String status,String currency,
    BigDecimal grossMerchantCollections,BigDecimal platformFees,BigDecimal platformFundedRefunds,
    BigDecimal merchantPayouts,BigDecimal collectedBalance,BigDecimal eligibleBalance,
    BigDecimal reservedForPayout,BigDecimal availableForPayout,BigDecimal deficit) {}
