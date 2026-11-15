package sn.groupia.directsales.infrastructure;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sn.groupia.directsales.application.DirectMerchantPaymentReadiness;
import sn.groupia.directsales.application.DirectOrderCancellation;
import sn.groupia.directsales.application.DirectOrderPreparation;
import sn.groupia.directsales.application.DirectOrderQueries;
import sn.groupia.directsales.application.DirectOrderReadiness;

/** Private merchant reads only; no public order/token access is exposed. */
@RestController
@RequestMapping("/api/organizations/{organizationId}/direct-orders")
class DirectOrderManagementController {
    private final DirectOrderQueries orders;
    private final DirectMerchantPaymentReadiness paymentReadiness;
    private final DirectOrderCancellation cancellations;
    private final DirectOrderPreparation preparation;
    private final DirectOrderReadiness readiness;
    private final sn.groupia.directsales.application.DirectOrderFulfillment fulfillment;

    DirectOrderManagementController(
            DirectOrderQueries orders,
            DirectMerchantPaymentReadiness paymentReadiness,
            DirectOrderCancellation cancellations,
            DirectOrderPreparation preparation,
            DirectOrderReadiness readiness,
            sn.groupia.directsales.application.DirectOrderFulfillment fulfillment) {
        this.fulfillment = fulfillment;
        this.orders = orders;
        this.paymentReadiness = paymentReadiness;
        this.cancellations = cancellations;
        this.preparation = preparation;
        this.readiness = readiness;
    }

    @GetMapping("/{orderId}/fulfillment-history")
    java.util.List<sn.groupia.directsales.application.DirectOrderFulfillment.Event>
            fulfillmentHistory(@PathVariable UUID organizationId, @PathVariable UUID orderId) {
        return fulfillment.history(organizationId, orderId);
    }

    @PostMapping("/{orderId}/fulfillment")
    sn.groupia.directsales.application.DirectOrderFulfillment.Event fulfill(
            @PathVariable UUID organizationId,
            @PathVariable UUID orderId,
            @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key") String key,
            @org.springframework.web.bind.annotation.RequestBody
                    sn.groupia.directsales.application.DirectOrderFulfillment.Command command) {
        return fulfillment.transition(organizationId, orderId, key, command);
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.Operation(operationId = "listDirectOrders")
    ResponseEntity<DirectOrderQueries.Page> list(
            @PathVariable UUID organizationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(orders.list(organizationId, page, size));
    }

    @PostMapping("/{orderId}/mark-ready")
    @io.swagger.v3.oas.annotations.Operation(operationId = "markDirectOrderReady")
    ResponseEntity<DirectOrderReadiness.Receipt> markReady(
            @PathVariable UUID organizationId, @PathVariable UUID orderId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(readiness.markReady(organizationId, orderId));
    }

    @PostMapping("/{orderId}/start-preparation")
    @io.swagger.v3.oas.annotations.Operation(operationId = "startDirectOrderPreparation")
    ResponseEntity<DirectOrderPreparation.Receipt> startPreparation(
            @PathVariable UUID organizationId, @PathVariable UUID orderId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(preparation.start(organizationId, orderId));
    }

    @PostMapping("/{orderId}/cancel")
    @io.swagger.v3.oas.annotations.Operation(operationId = "cancelDirectUnpaidOrder")
    ResponseEntity<DirectOrderCancellation.Receipt> cancel(
            @PathVariable UUID organizationId, @PathVariable UUID orderId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(cancellations.cancel(organizationId, orderId));
    }

    @GetMapping("/{orderId}/payment-status")
    @io.swagger.v3.oas.annotations.Operation(operationId = "getDirectOrderPaymentReadiness")
    ResponseEntity<DirectMerchantPaymentReadiness.Status> paymentStatus(
            @PathVariable UUID organizationId, @PathVariable UUID orderId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(paymentReadiness.get(organizationId, orderId));
    }

    @GetMapping("/{orderId}")
    @io.swagger.v3.oas.annotations.Operation(operationId = "getDirectOrder")
    ResponseEntity<DirectOrderQueries.Detail> get(
            @PathVariable UUID organizationId, @PathVariable UUID orderId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(orders.get(organizationId, orderId));
    }
}
