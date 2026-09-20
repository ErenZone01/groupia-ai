package sn.groupia.payment.infrastructure.web;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import sn.groupia.payment.application.service.*;
@RestController
class PaymentController {
    private final PaymentWebhooks webhooks;
    private final FinancialQueries queries;
    PaymentController(PaymentWebhooks webhooks,FinancialQueries queries) { this.webhooks=webhooks; this.queries=queries; }
    @PostMapping("/api/payment/webhooks/{provider}")
    ResponseEntity<Void> webhook(@PathVariable String provider,@RequestBody byte[] payload,@RequestHeader HttpHeaders headers) {
        webhooks.receive(provider,payload,headers.toSingleValueMap());
        return ResponseEntity.accepted().build();
    }
    @GetMapping("/api/organizations/{organizationId}/payment-requests")
    FinancialQueries.Page list(@PathVariable UUID organizationId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return queries.list(organizationId,page,size);
    }
    @GetMapping("/api/organizations/{organizationId}/payment-requests/{id}")
    FinancialQueries.Detail get(@PathVariable UUID organizationId,@PathVariable UUID id) { return queries.get(organizationId,id); }
}
