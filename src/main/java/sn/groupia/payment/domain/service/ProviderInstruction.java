package sn.groupia.payment.domain.service;
import java.math.BigDecimal;
import java.util.UUID;
import sn.groupia.payment.api.PaymentPurpose;
public record ProviderInstruction(UUID paymentId, UUID paymentRequestId, UUID orderId, PaymentPurpose purpose,
        String provider, String providerAccountReference, String merchantReference, String providerTransactionId,
        String providerIdempotencyKey, BigDecimal amount, String currency) {
    public ProviderInstruction withTransactionId(String transactionId) {
        return new ProviderInstruction(paymentId,paymentRequestId,orderId,purpose,provider,providerAccountReference,
                merchantReference,transactionId,providerIdempotencyKey,amount,currency);
    }
}
