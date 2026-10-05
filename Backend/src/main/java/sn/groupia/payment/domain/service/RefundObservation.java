package sn.groupia.payment.domain.service;
import java.math.BigDecimal;
import java.time.Instant;
public record RefundObservation(String provider,String account,String merchantReference,String transactionId,
    BigDecimal amount,String currency,String recipientPhone,String status,Instant occurredAt) {
    @Override public String toString(){return "RefundObservation[redacted]";}
}

