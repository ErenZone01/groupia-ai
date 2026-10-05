package sn.groupia.ordering.api.dto;
import java.math.BigDecimal;
import java.util.UUID;
/** Safe for the authorized customer; contains no provider reference, secret or checkout URL. */
public record ShippingSettlementView(BigDecimal finalShippingAmount,BigDecimal initialShippingPaidAmount,
    BigDecimal additionalShippingPaidAmount,BigDecimal totalShippingPaidAmount,Status status,
    BigDecimal amountDue,BigDecimal creditAmount,String currency,UUID currentShippingCalculationId,
    Long revision,CurrentObligation currentPaymentRequest,boolean paymentInProgress,
    BigDecimal successfulShippingCreditRefunds,BigDecimal netShippingPaid,boolean creditStableForRefund,
    java.util.List<sn.groupia.payment.api.dto.RefundSummary> refunds) {
    public enum Status { NOT_FINALIZED, BALANCE_DUE, SETTLED, CREDIT_DUE }
    public record CurrentObligation(String status,BigDecimal amount) {}
}
