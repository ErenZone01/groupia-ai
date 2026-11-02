package sn.groupia.directsales.infrastructure;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sn.groupia.directsales.application.DirectOrderTrackingService;

@RestController
class DirectOrderTrackingController {
    public record LookupRequest(String token) {}

    private final DirectOrderTrackingService tracking;

    DirectOrderTrackingController(DirectOrderTrackingService tracking) {
        this.tracking = tracking;
    }

    @PostMapping("/api/public/direct-orders/lookup")
    @io.swagger.v3.oas.annotations.Operation(operationId = "readCustomerDirectOrderByToken")
    ResponseEntity<DirectOrderTrackingService.Tracking> lookup(@RequestBody LookupRequest input) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.REFERRER_POLICY, "no-referrer")
                .header("X-Robots-Tag", "noindex, nofollow")
                .body(tracking.lookup(input == null ? null : input.token()));
    }
}
