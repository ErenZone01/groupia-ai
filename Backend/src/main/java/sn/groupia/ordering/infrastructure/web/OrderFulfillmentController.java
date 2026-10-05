package sn.groupia.ordering.infrastructure.web;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.ordering.application.service.OrderFulfillment;
import sn.groupia.ordering.api.dto.FulfillmentBatch;
import sn.groupia.ordering.domain.model.FulfillmentEvent.Type;
@RestController @RequestMapping("/api/organizations/{organizationId}/grouping-campaigns/{campaignId}")
class OrderFulfillmentController {
    private final OrderFulfillment service;
    OrderFulfillmentController(OrderFulfillment service){this.service=service;}
    @GetMapping("/fulfillment") @Operation(operationId="distributionBatch",summary="Distribution et retraits")
    ResponseEntity<FulfillmentBatch> list(@PathVariable UUID organizationId,@PathVariable UUID campaignId){return response(service.list(organizationId,campaignId));}
    @PostMapping("/orders/{orderId}/ready-for-pickup") @Operation(operationId="markOrderReady",summary="Attester la disponibilité physique")
    ResponseEntity<FulfillmentBatch> ready(@PathVariable UUID organizationId,@PathVariable UUID campaignId,@PathVariable UUID orderId,@Valid @RequestBody ConfirmFulfillment body){
        return response(service.change(organizationId,campaignId,orderId,body.version(),Type.READY_FOR_PICKUP,Boolean.TRUE.equals(body.confirmed()),null));
    }
    @PostMapping("/orders/{orderId}/revoke-readiness") @Operation(operationId="revokeOrderReadiness",summary="Retirer la mise à disposition avec motif")
    ResponseEntity<FulfillmentBatch> revoke(@PathVariable UUID organizationId,@PathVariable UUID campaignId,@PathVariable UUID orderId,@Valid @RequestBody RevokeReadiness body){
        return response(service.change(organizationId,campaignId,orderId,body.version(),Type.READINESS_REVOKED,false,body.reason()));
    }
    @PostMapping("/orders/{orderId}/picked-up") @Operation(operationId="confirmOrderPickup",summary="Attester la remise physique complète")
    ResponseEntity<FulfillmentBatch> pickup(@PathVariable UUID organizationId,@PathVariable UUID campaignId,@PathVariable UUID orderId,@Valid @RequestBody ConfirmFulfillment body){
        return response(service.change(organizationId,campaignId,orderId,body.version(),Type.PICKED_UP,Boolean.TRUE.equals(body.confirmed()),null));
    }
    private ResponseEntity<FulfillmentBatch> response(FulfillmentBatch body){return ResponseEntity.ok().header("Cache-Control","no-store").body(body);}
    record ConfirmFulfillment(@NotNull @PositiveOrZero Long version,@NotNull Boolean confirmed){}
    record RevokeReadiness(@NotNull @PositiveOrZero Long version,@NotBlank @Size(max=1000) String reason){}
}
