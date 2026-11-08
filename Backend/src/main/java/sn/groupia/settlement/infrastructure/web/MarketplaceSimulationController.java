package sn.groupia.settlement.infrastructure.web;

import io.swagger.v3.oas.annotations.Operation;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sn.groupia.settlement.api.dto.MarketplaceSimulationView;
import sn.groupia.settlement.application.service.*;

@RestController
@RequestMapping("/api/organizations/{organizationId}")
class MarketplaceSimulationController {
    private final MarketplaceSimulationTransactions transactions;
    private final MarketplaceSimulations simulations;

    MarketplaceSimulationController(MarketplaceSimulationTransactions t, MarketplaceSimulations s) {
        transactions = t;
        simulations = s;
    }

    private static <T> ResponseEntity<T> response(T value) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(value);
    }

    @GetMapping("/provider-collections/{collectionId}/simulation-quote")
    @Operation(operationId = "marketplaceSimulationQuote")
    ResponseEntity<MarketplaceSimulationTransactions.SimulationQuote> quote(
            @PathVariable UUID organizationId,
            @PathVariable UUID collectionId,
            @RequestParam String kind) {
        return response(transactions.quote(organizationId, collectionId, kind));
    }

    @GetMapping("/provider-collections/{collectionId}/simulations")
    @Operation(operationId = "marketplaceSimulationHistory")
    ResponseEntity<List<MarketplaceSimulationView>> history(
            @PathVariable UUID organizationId, @PathVariable UUID collectionId) {
        return response(transactions.history(organizationId, collectionId));
    }

    @PostMapping("/provider-collections/{collectionId}/simulations")
    @Operation(operationId = "startMarketplaceSimulation")
    ResponseEntity<MarketplaceSimulationView> start(
            @PathVariable UUID organizationId,
            @PathVariable UUID collectionId,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody MarketplaceSimulationTransactions.SimulationCommand command) {
        return response(simulations.start(organizationId, collectionId, key, command));
    }

    @PostMapping("/marketplace-simulations/{simulationId}/reconcile")
    @Operation(operationId = "reconcileMarketplaceSimulation")
    ResponseEntity<MarketplaceSimulationView> reconcile(
            @PathVariable UUID organizationId, @PathVariable UUID simulationId) {
        return response(simulations.reconcile(organizationId, simulationId));
    }
}
