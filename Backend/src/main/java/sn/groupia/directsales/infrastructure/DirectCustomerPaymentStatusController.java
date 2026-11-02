package sn.groupia.directsales.infrastructure;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sn.groupia.directsales.application.DirectCustomerPaymentStatus;

/**
 * Token-only status inspection. The request body avoids putting bearer credentials in URLs. Never
 * exposes provider IDs, payment links, personal details or other orders.
 */
@RestController
class DirectCustomerPaymentStatusController {
    public record LookupRequest(String token) {}

    private final DirectCustomerPaymentStatus statuses;

    DirectCustomerPaymentStatusController(DirectCustomerPaymentStatus statuses) {
        this.statuses = statuses;
    }

    @PostMapping("/api/public/direct-orders/payment-status")
    @io.swagger.v3.oas.annotations.Operation(operationId = "readDirectPaymentPreparationByToken")
    ResponseEntity<DirectCustomerPaymentStatus.Status> lookup(@RequestBody LookupRequest input) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header("X-Robots-Tag", "noindex, nofollow")
                .body(statuses.lookup(input == null ? null : input.token()));
    }
}
