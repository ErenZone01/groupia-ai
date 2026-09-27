package sn.groupia.ordering.infrastructure.web;

import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.ordering.api.dto.SupplierSummaryView;
import sn.groupia.ordering.application.service.SupplierSummaryQueries;

@RestController
class SupplierSummaryController {
    private final SupplierSummaryQueries queries;

    SupplierSummaryController(SupplierSummaryQueries queries) {
        this.queries = queries;
    }

    @Operation(operationId = "supplierSummary", summary = "Récapitulatif fournisseur du groupage",
            description = "Lecture autorisée VIEW_ORDERS. Commandes CONFIRMED et COMPLETED uniquement. "
                    + "Quantités et marchandises XOF agrégées depuis les snapshots historiques, hors frais et transport.")
    @GetMapping("/api/organizations/{organizationId}/grouping-campaigns/{campaignId}/supplier-summary")
    ResponseEntity<SupplierSummaryView> get(@PathVariable UUID organizationId, @PathVariable UUID campaignId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(queries.get(organizationId, campaignId));
    }
}
