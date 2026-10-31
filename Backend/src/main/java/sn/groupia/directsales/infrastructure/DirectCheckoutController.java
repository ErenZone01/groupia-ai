package sn.groupia.directsales.infrastructure;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import sn.groupia.directsales.application.DirectCheckoutService;

@RestController
class DirectCheckoutController {
    private final DirectCheckoutService checkout;

    DirectCheckoutController(DirectCheckoutService checkout) {
        this.checkout = checkout;
    }

    @PostMapping("/api/public/direct-stores/{publicIdentifier}/checkout")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "Direct order draft created; no payment initiated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Idempotent retry; no duplicate order")
    })
    ResponseEntity<DirectCheckoutService.Receipt> checkout(
            @PathVariable UUID publicIdentifier,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody DirectCheckoutService.Command command) {
        var result = checkout.checkout(publicIdentifier, key, command);
        return ResponseEntity.status(result.created() ? 201 : 200)
                .cacheControl(CacheControl.noStore())
                .body(result.receipt());
    }
}
