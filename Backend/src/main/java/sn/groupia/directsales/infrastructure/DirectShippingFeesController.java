package sn.groupia.directsales.infrastructure;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.directsales.application.DirectShippingFeesService;

/** Public anonymous shipping PREVIEW, separate from real order and payment operations. */
@RestController
class DirectShippingFeesController {
    private final DirectShippingFeesService fees;

    DirectShippingFeesController(DirectShippingFeesService fees) {
        this.fees = fees;
    }

    @GetMapping("/api/organizations/{organizationId}/direct-store/shipping-fees")
    @io.swagger.v3.oas.annotations.Operation(operationId = "getDirectShippingFees")
    ResponseEntity<DirectShippingFeesService.Settings> manager(@PathVariable UUID organizationId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(fees.manager(organizationId));
    }

    @PutMapping("/api/organizations/{organizationId}/direct-store/shipping-fees")
    @io.swagger.v3.oas.annotations.Operation(operationId = "updateDirectShippingFees")
    ResponseEntity<DirectShippingFeesService.Settings> update(
            @PathVariable UUID organizationId,
            @RequestBody DirectShippingFeesService.Settings input) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(fees.save(organizationId, input));
    }

    @PostMapping("/api/public/direct-stores/{publicIdentifier}/shipping-quote")
    @io.swagger.v3.oas.annotations.Operation(operationId = "quoteDirectShipping")
    ResponseEntity<DirectShippingFeesService.Preview> quote(
            @PathVariable UUID publicIdentifier,
            @RequestBody DirectShippingFeesService.PreviewRequest input) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(fees.preview(publicIdentifier, input));
    }
}
