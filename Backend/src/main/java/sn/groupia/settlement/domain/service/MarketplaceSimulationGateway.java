package sn.groupia.settlement.domain.service;

import java.math.BigDecimal;
import java.util.UUID;

/** Local simulation protocol, NOT a SasPay API contract. No implementation may send real money. */
public interface MarketplaceSimulationGateway {
    record Command(
            UUID id,
            UUID organizationId,
            UUID collectionId,
            String kind,
            String sourceProvider,
            String sourceAccount,
            String currency,
            BigDecimal recipientNet,
            BigDecimal fee) {}

    record Proof(
            Command command,
            String status,
            String reference,
            BigDecimal recipientNet,
            BigDecimal debited) {}

    Proof execute(Command command);

    Proof verify(Command command);
}
