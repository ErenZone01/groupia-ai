package sn.groupia.ordering.api.dto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

public record OrderReceipt(String orderNumber, String status, String fulfillmentStatus,
        BigDecimal goodsSubtotal, BigDecimal platformFeeAmount, BigDecimal estimatedShippingAmount,
        BigDecimal initialShippingChargeAmount, BigDecimal initialAmountDue, String currency,
        Instant paymentDeadline, Instant placedAt, List<OrderLineView> items, @JsonInclude(JsonInclude.Include.NON_NULL) String trackingToken) {}
