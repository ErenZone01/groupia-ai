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
import sn.groupia.shipping.api.*;
@RestController
@RequestMapping("/api/organizations/{organizationId}/grouping-campaigns/{campaignId}")
class CampaignLogisticsController {
    private final CampaignLogistics logistics;
    CampaignLogisticsController(CampaignLogistics logistics){this.logistics=logistics;}
    record ShipCampaign(@NotNull @PositiveOrZero Long version, Instant departedAt,
        Instant expectedArrivalAt,@Size(max=160) String trackingReference,@Size(max=2000) String note,LogisticsSource source) {}
    record ArriveCampaign(@NotNull @PositiveOrZero Long version,Instant arrivedAt,LogisticsSource source,@Size(max=2000) String note) {}
    record UpdateShipment(@NotNull @PositiveOrZero Long version,@Size(max=160) String providerName,
        @Size(max=300) String providerContact,@Size(max=160) String trackingReference,@Size(max=2000) String note,LogisticsSource source) {}
    record UpdateShipmentEta(@NotNull @PositiveOrZero Long version,Instant expectedArrivalAt,LogisticsSource source,@Size(max=2000) String note) {}
    record AddShipmentFact(@NotNull @PositiveOrZero Long version,@NotNull LogisticsFactType type,
        Instant occurredAt,LogisticsSource source,@Size(max=2000) String note) {}
    @PostMapping("/ship") @Operation(operationId="shipCampaign",summary="Confirmer le départ communiqué, date réelle facultative")
    ResponseEntity<CampaignShipmentView> ship(@PathVariable UUID organizationId,@PathVariable UUID campaignId,@Valid @RequestBody ShipCampaign b) {
        return response(logistics.ship(organizationId,campaignId,b.version(),b.departedAt(),b.expectedArrivalAt(),b.trackingReference(),b.note(),b.source()));
    }
    @PostMapping("/arrive") @Operation(operationId="arriveCampaign",summary="Confirmer l’arrivée à destination, date réelle facultative")
    ResponseEntity<CampaignShipmentView> arrive(@PathVariable UUID organizationId,@PathVariable UUID campaignId,@Valid @RequestBody ArriveCampaign b) {
        return response(logistics.arrive(organizationId,campaignId,b.version(),b.arrivedAt(),b.source(),b.note()));
    }
    @PutMapping("/shipment") @Operation(operationId="updateCampaignShipment",summary="Créer ou enrichir le dossier logistique privé")
    ResponseEntity<CampaignShipmentView> enrich(@PathVariable UUID organizationId,@PathVariable UUID campaignId,@Valid @RequestBody UpdateShipment b) {
        return response(logistics.enrich(organizationId,campaignId,b.version(),b.providerName(),b.providerContact(),b.trackingReference(),b.note(),b.source()));
    }
    @PutMapping("/shipment/eta") @Operation(operationId="updateCampaignShipmentEta",summary="Réviser ou retirer l’arrivée estimée avec historique")
    ResponseEntity<CampaignShipmentView> eta(@PathVariable UUID organizationId,@PathVariable UUID campaignId,@Valid @RequestBody UpdateShipmentEta b) {
        return response(logistics.estimate(organizationId,campaignId,b.version(),b.expectedArrivalAt(),b.source(),b.note()));
    }
    @PostMapping("/shipment/facts") @Operation(operationId="addCampaignShipmentFact",summary="Ajouter une information facultative à la timeline privée")
    ResponseEntity<CampaignShipmentView> fact(@PathVariable UUID organizationId,@PathVariable UUID campaignId,@Valid @RequestBody AddShipmentFact b) {
        return response(logistics.addFact(organizationId,campaignId,b.version(),b.type(),b.occurredAt(),b.source(),b.note()));
    }
    @GetMapping("/shipment") @Operation(operationId="readCampaignShipment",summary="Lire le dossier et la timeline privés du groupage")
    ResponseEntity<CampaignShipmentView> read(@PathVariable UUID organizationId,@PathVariable UUID campaignId) {
        return response(logistics.read(organizationId,campaignId));
    }
    private ResponseEntity<CampaignShipmentView> response(CampaignShipmentView view){return ResponseEntity.ok().header("Cache-Control","no-store").body(view);}
}
