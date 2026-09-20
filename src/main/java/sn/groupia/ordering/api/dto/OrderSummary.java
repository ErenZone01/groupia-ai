package sn.groupia.ordering.api.dto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record OrderSummary(UUID id, UUID campaignId, String orderNumber, String status,
        String fulfillmentStatus, BigDecimal initialAmountDue, String currency, Instant placedAt) {}
