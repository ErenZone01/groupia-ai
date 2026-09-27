package sn.groupia.procurement.infrastructure.web;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.media.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import sn.groupia.procurement.api.dto.SupplierOrderResponse;
import sn.groupia.procurement.application.service.SupplierPurchases;
@RestController
@RequestMapping("/api/organizations/{organizationId}/grouping-campaigns/{campaignId}/supplier-order")
class SupplierOrderController {
    private final SupplierPurchases purchases;
    SupplierOrderController(SupplierPurchases purchases){this.purchases=purchases;}
    record CreateSupplierOrder(@NotNull @PositiveOrZero Long campaignVersion) {}
    @PostMapping
    @Operation(operationId="createSupplierOrder",summary="Déclarer la commande fournisseur",
        description="MANAGE_GROUPINGS et VIEW_ORDERS. CLOSED uniquement, sans paiement encore actif. Version originale identifie le rejeu idempotent. 409 SUPPLIER_ORDER_PENDING_PAYMENTS fournit details.pendingOrderCount sans données clientes.")
    @ApiResponses({@ApiResponse(responseCode="201",description="Commande fournisseur créée",content=@Content(schema=@Schema(implementation=SupplierOrderResponse.class))),
        @ApiResponse(responseCode="200",description="Rejeu de la même intention",content=@Content(schema=@Schema(implementation=SupplierOrderResponse.class)))})
    ResponseEntity<SupplierOrderResponse> create(@PathVariable UUID organizationId,@PathVariable UUID campaignId,@Valid @RequestBody CreateSupplierOrder request) {
        var result=purchases.create(organizationId,campaignId,request.campaignVersion());
        return ResponseEntity.status(result.replayed()?200:201).cacheControl(CacheControl.noStore()).body(result);
    }
    @GetMapping
    @Operation(operationId="readSupplierOrder",summary="Lire la commande fournisseur figée",description="VIEW_ORDERS. Le bon est immuable ; campaign et lateAcceptedOrderCount sont des métadonnées opérationnelles actuelles. Aucun recalcul du bon.")
    ResponseEntity<SupplierOrderResponse> read(@PathVariable UUID organizationId,@PathVariable UUID campaignId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(purchases.read(organizationId,campaignId));
    }
}
