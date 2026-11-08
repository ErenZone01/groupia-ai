package sn.groupia.payment.infrastructure.web;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.payment.application.service.CommercialRefundService;

@RestController
@RequestMapping("/api/organizations/{organizationId}/commercial-refunds/{caseId}")
class CommercialRefundController {
    private final CommercialRefundService service;

    CommercialRefundController(CommercialRefundService s) {
        service = s;
    }

    @GetMapping
    @Operation(operationId = "commercialRefundCase")
    ResponseEntity<CommercialRefundService.RefundCaseDetail> read(
            @PathVariable UUID organizationId, @PathVariable UUID caseId) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(service.detail(organizationId, caseId));
    }

    @PostMapping("/external-declaration")
    @Operation(operationId = "declareExternalCommercialRefund")
    ResponseEntity<CommercialRefundService.RefundCaseDetail> declare(
            @PathVariable UUID organizationId,
            @PathVariable UUID caseId,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody CommercialRefundService.Declaration command) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(service.declare(organizationId, caseId, key, command));
    }
}
