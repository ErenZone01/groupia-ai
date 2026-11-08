package sn.groupia.directsales.infrastructure;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.directsales.application.DirectPaidCancellations;
import sn.groupia.payment.api.dto.*;

@RestController
@RequestMapping("/api/organizations/{organizationId}/direct-orders/{orderId}/paid-cancellation")
class DirectPaidCancellationController {
    private final DirectPaidCancellations service;

    DirectPaidCancellationController(DirectPaidCancellations s) {
        service = s;
    }

    @GetMapping
    @Operation(operationId = "directPaidCancellation")
    ResponseEntity<CommercialCancellationView> read(
            @PathVariable UUID organizationId, @PathVariable UUID orderId) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(service.read(organizationId, orderId));
    }

    @PostMapping
    @Operation(operationId = "cancelPaidDirectOrder")
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
