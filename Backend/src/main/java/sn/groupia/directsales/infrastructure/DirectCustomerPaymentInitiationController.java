package sn.groupia.directsales.infrastructure;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import sn.groupia.directsales.application.DirectCustomerPaymentInitiation;
import sn.groupia.payment.api.dto.PaymentAttemptView;

/**
 * Bearer token travels in the POST body, never a GET URL. No browser-provided target, amount,
 * provider, phone or merchant account is accepted. Responses must not be cached or indexed.
 */
@RestController
class DirectCustomerPaymentInitiationController {
    public record InitiationRequest(String token, java.util.UUID feeQuoteId, String provider) {
        public InitiationRequest(String token, java.util.UUID feeQuoteId) {
            this(token, feeQuoteId, null);
        }
        @Override
        public String toString() {
            return "InitiationRequest[redacted]";
        }
    }

    public record QuoteRequest(String token) {
        @Override
        public String toString() {
            return "QuoteRequest[redacted]";
        }
    }

    private final DirectCustomerPaymentInitiation initiation;

    DirectCustomerPaymentInitiationController(DirectCustomerPaymentInitiation initiation) {
        this.initiation = initiation;
    }

    @PostMapping("/api/public/direct-orders/payment-quotes")
    @io.swagger.v3.oas.annotations.Operation(operationId = "quoteDirectOrderPaymentByToken")
    ResponseEntity<java.util.List<sn.groupia.payment.api.dto.PaymentFeeQuoteView>> quotes(
            @RequestBody QuoteRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header("X-Robots-Tag", "noindex, nofollow")
                .body(initiation.quotes(request == null ? null : request.token()));
    }

    @PostMapping("/api/public/direct-orders/payments")
    @io.swagger.v3.oas.annotations.Operation(operationId = "initiateDirectOrderPaymentByToken")
    ResponseEntity<PaymentAttemptView> initiate(
            @RequestBody InitiationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        PaymentAttemptView attempt =
                initiation.initiate(
                        request == null ? null : request.token(),
                        idempotencyKey,
                        request == null ? null : request.feeQuoteId(),
                        request == null || request.provider() == null ? "BICTORYS" : request.provider());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header("X-Robots-Tag", "noindex, nofollow")
                .body(attempt);
    }
}
