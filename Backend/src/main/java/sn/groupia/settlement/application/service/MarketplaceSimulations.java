package sn.groupia.settlement.application.service;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.settlement.api.dto.MarketplaceSimulationView;
import sn.groupia.settlement.domain.service.MarketplaceSimulationGateway;

@Service
@Transactional(propagation = Propagation.NEVER)
public class MarketplaceSimulations {
    private final MarketplaceSimulationTransactions transactions;
    private final MarketplaceSimulationGateway gateway;

    public MarketplaceSimulations(
            MarketplaceSimulationTransactions t, MarketplaceSimulationGateway g) {
        transactions = t;
        gateway = g;
    }

    public MarketplaceSimulationView start(
            UUID org,
            UUID collection,
            UUID key,
            MarketplaceSimulationTransactions.SimulationCommand command) {
        return dispatch(transactions.reserve(org, collection, key, command));
    }

    public MarketplaceSimulationView reconcile(UUID org, UUID id) {
        return dispatch(transactions.command(org, id));
    }

    private MarketplaceSimulationView dispatch(MarketplaceSimulationGateway.Command command) {
        var current = transactions.get(command.organizationId(), command.id());
        if (List.of("SUCCEEDED", "FAILED").contains(current.status())) return current;
        MarketplaceSimulationGateway.Proof proof = null;
        try {
            proof = gateway.verify(command);
            if (proof == null) gateway.execute(command);
            // Even a successful initiation is insufficient: read the durable provider proof
            // separately.
            proof = gateway.verify(command);
        } catch (RuntimeException ignored) {
            /* ambiguous result retains the reservation and same intent */
        }
        return transactions.finish(command, proof);
    }
}
