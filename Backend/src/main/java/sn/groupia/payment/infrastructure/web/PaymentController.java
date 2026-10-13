package sn.groupia.payment.infrastructure.web;

import io.swagger.v3.oas.annotations.Operation;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.application.service.*;

@RestController
class PaymentController {
    private final PaymentWebhooks webhooks;
    private final FinancialQueries queries;

    PaymentController(PaymentWebhooks webhooks, FinancialQueries queries) {
        this.webhooks = webhooks;
        this.queries = queries;
    }

    @PostMapping("/api/payment/webhooks/{provider}")
    ResponseEntity<Void> webhook(
            @PathVariable String provider,
            @RequestBody byte[] payload,
            @RequestHeader HttpHeaders headers) {
        webhooks.receive(provider, payload, headers.toSingleValueMap());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/api/organizations/{organizationId}/payment-requests")
    @Operation(operationId = "listPaymentRequests")
    ResponseEntity<PaymentRequestsPageView> list(
            @PathVariable UUID organizationId,
            @RequestParam(required = false) PaymentOperationalStatus operationalStatus,
            @RequestParam(required = false) PaymentPurpose purpose,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        queries.list(
                                organizationId, operationalStatus, purpose, orderId, page, size));
    }

    @GetMapping("/api/organizations/{organizationId}/payment-requests/{id}")
    @Operation(operationId = "getPaymentRequest")
    ResponseEntity<PaymentRequestDetailView> get(
            @PathVariable UUID organizationId, @PathVariable UUID id) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(queries.get(organizationId, id));
    }
}
