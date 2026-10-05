package sn.groupia.ordering.infrastructure.web;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.ordering.application.service.OrderCredits;
import sn.groupia.payment.api.RefundOperations;
import sn.groupia.shared.api.ApiException;
@RestController @RequestMapping("/api/organizations/{organizationId}/orders/{orderId}/shipping-credit")
class OrderCreditController {
    private final OrderCredits credits;private final RefundOperations refunds;
    OrderCreditController(OrderCredits c,RefundOperations r){credits=c;refunds=r;}
    @GetMapping @Operation(operationId="shippingCredit",summary="Consulter la régularisation du crédit transport")
    ResponseEntity<OrderCredits.CreditResolution> get(@PathVariable UUID organizationId,@PathVariable UUID orderId){return response(credits.read(organizationId,orderId));}
    @PostMapping @Operation(operationId="resolveShippingCredit",summary="Régulariser tout le crédit transport après retrait")
    ResponseEntity<OrderCredits.CreditResolution> resolve(@PathVariable UUID organizationId,@PathVariable UUID orderId,@Valid @RequestBody ResolveCredit body){
        var refund=credits.reserve(organizationId,orderId,body.version(),body.method(),body.externalMethod(),body.reference(),body.note(),Boolean.TRUE.equals(body.confirmed()));
        refunds.dispatch(organizationId,refund.id());
        return response(credits.read(organizationId,orderId));
    }
    private ResponseEntity<OrderCredits.CreditResolution> response(OrderCredits.CreditResolution view){return ResponseEntity.ok().header("Cache-Control","no-store").body(view);}
    record ResolveCredit(@NotNull @PositiveOrZero Long version,@NotBlank String method,@NotBlank String externalMethod,
        @Size(max=160) String reference,@Size(max=1000) String note,@NotNull Boolean confirmed){
        @JsonAnySetter public void reject(String field,Object value){throw new ApiException(400,"REFUND_INPUT_INVALID","Champ non autorisé.");}
    }
}

