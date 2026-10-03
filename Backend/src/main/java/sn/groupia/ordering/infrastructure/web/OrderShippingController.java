package sn.groupia.ordering.infrastructure.web;

import java.util.*;
import java.math.BigDecimal;
import java.time.Instant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import sn.groupia.ordering.application.service.OrderShipping;
import sn.groupia.ordering.api.dto.OrderShippingRow;
import sn.groupia.shipping.api.*;
import sn.groupia.shipping.api.dto.*;

@RestController
@RequestMapping("/api/organizations/{organizationId}/grouping-campaigns/{campaignId}")
class OrderShippingController {
    private final OrderShipping service;
    OrderShippingController(OrderShipping service){this.service=service;}
    @GetMapping("/shipping-assessments")
    @Operation(operationId="orderShippingBatch",summary="Transport des commandes confirmées")
    ResponseEntity<List<OrderShippingRow>> list(@PathVariable UUID organizationId,@PathVariable UUID campaignId){
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.list(organizationId,campaignId));
    }
    @PostMapping("/orders/{orderId}/shipping-measurements")
    @Operation(operationId="recordShippingMeasurement",summary="Enregistrer une information physique")
    ShippingAssessmentView measure(@PathVariable UUID organizationId,@PathVariable UUID campaignId,@PathVariable UUID orderId,
        @Valid @RequestBody RecordShippingMeasurement body){
        return service.measure(organizationId,campaignId,orderId,body.version(),body.information());
    }
    @PostMapping("/orders/{orderId}/shipping-preview")
    @Operation(operationId="previewShipping",summary="Aperçu sans finalisation")
    ShippingExplanation preview(@PathVariable UUID organizationId,@PathVariable UUID campaignId,@PathVariable UUID orderId,
        @Valid @RequestBody FinalShipping body){return service.preview(organizationId,campaignId,orderId,body.input());}
    @PostMapping("/orders/{orderId}/shipping-finalizations")
    @Operation(operationId="finalizeShipping",summary="Finaliser ou corriger le transport")
    ShippingAssessmentView finalizeShipping(@PathVariable UUID organizationId,@PathVariable UUID campaignId,@PathVariable UUID orderId,
        @Valid @RequestBody FinalShipping body){return service.finalizeShipping(organizationId,campaignId,orderId,body.input());}
    record RecordShippingMeasurement(@NotNull @PositiveOrZero Long version,@NotNull @Valid MeasurementInput information){}
    record FinalShipping(@NotNull @PositiveOrZero Long version,@NotNull CalculationOrigin origin,UUID measurementId,
        BigDecimal quotedAmount,String currency,LogisticsSource source,Instant occurredAt,
        @Size(max=2000) String note,@Size(max=1000) String correctionReason){
        FinalShippingInput input(){return new FinalShippingInput(version,origin,measurementId,quotedAmount,currency,source,occurredAt,note,correctionReason);}
    }
}
