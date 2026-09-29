package sn.groupia.ordering.api.dto;
import java.math.BigDecimal;
import java.time.Instant;
import sn.groupia.grouping.api.dto.PublicCampaignProgress;
public record MyOrderSummary(String orderNumber,Instant placedAt,String status,String fulfillmentStatus,
    BigDecimal amount,String currency,PublicCampaignProgress groupage) {}
