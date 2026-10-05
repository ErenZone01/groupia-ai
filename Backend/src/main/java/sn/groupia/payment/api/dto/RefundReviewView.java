package sn.groupia.payment.api.dto;
import java.util.UUID;
/** Private operations queue, scoped by VIEW_PAYMENTS. */
public record RefundReviewView(UUID orderId,RefundView refund,String reason) {}
