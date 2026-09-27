package sn.groupia.grouping.infrastructure.web;
import java.time.Instant;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import sn.groupia.grouping.api.dto.CampaignShipmentView;
import sn.groupia.grouping.application.service.CampaignLogistics;
@RestController
@RequestMapping("/api/organizations/{organizationId}/grouping-campaigns/{campaignId}")
class CampaignLogisticsController {
    private final CampaignLogistics logistics;
    CampaignLogisticsController(CampaignLogistics logistics){this.logistics=logistics;}
    record ShipCampaign(@NotNull @PositiveOrZero Long version,@NotNull Instant departedAt,
            Instant expectedArrivalAt,@Size(max=160) String trackingReference,@Size(max=2000) String note) {}
    record ArriveCampaign(@NotNull @PositiveOrZero Long version,Instant arrivedAt) {}
    @PostMapping("/ship") @Operation(operationId="shipCampaign",summary="Déclarer le départ de la marchandise")
    ResponseEntity<CampaignShipmentView> ship(@PathVariable UUID organizationId,@PathVariable UUID campaignId,@Valid @RequestBody ShipCampaign body) {
        return response(logistics.ship(organizationId,campaignId,body.version(),body.departedAt(),body.expectedArrivalAt(),body.trackingReference(),body.note()));
    }
    @PostMapping("/arrive") @Operation(operationId="arriveCampaign",summary="Déclarer l’arrivée de la marchandise")
    ResponseEntity<CampaignShipmentView> arrive(@PathVariable UUID organizationId,@PathVariable UUID campaignId,@Valid @RequestBody ArriveCampaign body) {
        return response(logistics.arrive(organizationId,campaignId,body.version(),body.arrivedAt()));
    }
    @GetMapping("/shipment") @Operation(operationId="readCampaignShipment",summary="Lire la logistique privée du groupage")
    ResponseEntity<CampaignShipmentView> read(@PathVariable UUID organizationId,@PathVariable UUID campaignId) {
        return response(logistics.read(organizationId,campaignId));
    }
    private ResponseEntity<CampaignShipmentView> response(CampaignShipmentView view){return ResponseEntity.ok().header("Cache-Control","no-store").body(view);}
}
