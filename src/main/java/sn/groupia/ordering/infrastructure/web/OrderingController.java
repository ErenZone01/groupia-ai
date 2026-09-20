package sn.groupia.ordering.infrastructure.web;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.ordering.api.dto.*;
import sn.groupia.ordering.application.command.CheckoutCommand;
import sn.groupia.ordering.application.service.*;

@RestController
class OrderingController {
    private final CheckoutService checkout;
    private final OrderQueries queries;
    private final TrackingAttempts attempts;
    private final OrderPayments payments;
    OrderingController(CheckoutService checkout, OrderQueries queries, TrackingAttempts attempts, OrderPayments payments) {
        this.checkout = checkout; this.queries = queries; this.attempts = attempts; this.payments=payments;
    }
    @PostMapping("/api/public/groupings/{publicIdentifier}/checkout")
    ResponseEntity<Object> checkout(@PathVariable UUID publicIdentifier,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody CheckoutCommand command) {
        var result = checkout.checkout(publicIdentifier, key, command);
        return ResponseEntity.status(result.created() ? 201 : 200).header("Cache-Control", "no-store").body(result.body());
    }
    record InitiatePayment(String provider) {}
    @PostMapping("/api/public/orders/{orderNumber}/payments")
    ResponseEntity<sn.groupia.payment.api.dto.PaymentAttemptView> initiate(@PathVariable String orderNumber,
            @RequestHeader(value="X-Order-Tracking-Token",required=false) String token,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestBody InitiatePayment input, HttpServletRequest request) {
        attempts.check(request.getRemoteAddr());
        return ResponseEntity.ok().header("Cache-Control","no-store").body(payments.initiate(orderNumber,token,input.provider(),key));
    }
    @GetMapping("/api/public/orders/track")
    ResponseEntity<OrderReceipt> track(@RequestHeader(value = "X-Order-Tracking-Token", required = false) String token,
            HttpServletRequest request) {
        attempts.check(request.getRemoteAddr());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(queries.track(token));
    }
    @GetMapping("/api/organizations/{organizationId}/orders")
    OrderPage list(@PathVariable UUID organizationId, @RequestParam(required = false) UUID campaignId,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.list(organizationId, campaignId, status, page, size);
    }
    @GetMapping("/api/organizations/{organizationId}/orders/{orderId}")
    OrganizerOrderView get(@PathVariable UUID organizationId, @PathVariable UUID orderId) {
        return queries.get(organizationId, orderId);
    }
}
