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
        if ("saspay".equalsIgnoreCase(provider)) {
            // If absent while SasPay reports HTTP 401, rejection occurred before Spring MVC.
            org.slf4j.LoggerFactory.getLogger(PaymentController.class)
                    .debug("SasPay webhook reached Spring payment controller");
        }
        var authenticationHeaders = new HashMap<>(headers.toSingleValueMap());
        for (String name :
                List.of(
                        "X-Secret-Key",
                        "X-Webhook-Signature",
                        "X-Webhook-Timestamp",
                        "X-Webhook-Event")) {
            var values = headers.get(name);
            // Empty sentinel is deliberately invalid and preserves receipt persistence on
            // rejection.
            if (values != null && values.size() != 1) {
                authenticationHeaders.put("X-Webhook-Signature", "");
                authenticationHeaders.put("X-Webhook-Timestamp", "");
            }
        }
        webhooks.receive(provider, payload, authenticationHeaders);
        return "BICTORYS".equalsIgnoreCase(provider)
                ? ResponseEntity.ok().build()
                : ResponseEntity.accepted().build();
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
