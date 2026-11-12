package sn.groupia.settlement.infrastructure.web;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.settlement.application.service.ProviderCollectionLedger;

@RestController
@RequestMapping("/api/organizations/{organizationId}/provider-collections")
class ProviderCollectionController {
    private final ProviderCollectionLedger ledger;

    ProviderCollectionController(ProviderCollectionLedger l) {
        ledger = l;
    }

    @GetMapping
    @Operation(
            operationId = "providerCollections",
            summary = "Encaissements vérifiés par fournisseur et compte")
    ResponseEntity<ProviderCollectionLedger.CollectionPage> page(
            @PathVariable UUID organizationId, @RequestParam(required = false) UUID after) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(ledger.page(organizationId, after));
    }

    @GetMapping("/overview")
    @Operation(
            operationId = "providerCollectionsOverview",
            summary = "Cumuls historiques vente directe et groupage par compte fournisseur")
    ResponseEntity<ProviderCollectionLedger.CollectionOverview> overview(
            @PathVariable UUID organizationId) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(ledger.overview(organizationId));
    }

    @PostMapping("/refresh")
    @Operation(
            operationId = "refreshProviderCollections",
            summary = "Rattraper les règlements historiques, sans appel fournisseur")
    ResponseEntity<ProviderCollectionLedger.CollectionRefresh> refresh(
            @PathVariable UUID organizationId, @RequestParam(required = false) UUID after) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(ledger.refresh(organizationId, after));
    }
}
