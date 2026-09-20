package sn.groupia.payment.api.dto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.payment.api.PaymentStatus;
/** Safe receipt for the authorized buyer; excludes provider secrets, references and internal review details. */
public record PaymentAttemptView(UUID id, PaymentStatus status, BigDecimal expectedAmount, String currency,
        boolean reconciliationRequired, Instant initiatedAt) {}
