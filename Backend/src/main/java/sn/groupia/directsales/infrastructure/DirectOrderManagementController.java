package sn.groupia.directsales.infrastructure;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sn.groupia.directsales.application.DirectOrderQueries;

/** Private merchant reads only; no public order/token access is exposed. */
@RestController
@RequestMapping("/api/organizations/{organizationId}/direct-orders")
class DirectOrderManagementController {
    private final DirectOrderQueries orders;

    DirectOrderManagementController(DirectOrderQueries orders) {
        this.orders = orders;
    }

    @GetMapping
    ResponseEntity<DirectOrderQueries.Page> list(
            @PathVariable UUID organizationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(orders.list(organizationId, page, size));
    }

    @GetMapping("/{orderId}")
    ResponseEntity<DirectOrderQueries.Detail> get(
            @PathVariable UUID organizationId, @PathVariable UUID orderId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(orders.get(organizationId, orderId));
    }
}
