package sn.groupia.ordering.infrastructure.web;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.ordering.application.service.OrderCancellations;
import sn.groupia.payment.api.dto.*;

@RestController
@RequestMapping("/api/organizations/{organizationId}/orders/{orderId}/cancellation")
class OrderCancellationController {
    private final OrderCancellations service;

    OrderCancellationController(OrderCancellations s) {
        service = s;
    }

    @GetMapping
    @Operation(operationId = "orderCancellation")
    ResponseEntity<CommercialCancellationView> read(
            @PathVariable UUID organizationId, @PathVariable UUID orderId) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(service.read(organizationId, orderId));
    }

    @PostMapping
    @Operation(operationId = "cancelCommercialOrder")
    ResponseEntity<CommercialCancellationView> cancel(
            @PathVariable UUID organizationId,
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody CommercialCancellationCommand c) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(service.cancel(organizationId, orderId, key, c));
    }
}
