package sn.groupia.settlement.infrastructure.provider;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import sn.groupia.settlement.application.service.ProviderCollectionLedger;
import sn.groupia.settlement.domain.service.MarketplaceSimulationGateway;

/**
 * Durable local fake provider. Deliberately has no HTTP client, credentials or beneficiary address.
 */
@Component
public class LocalMarketplaceSimulator implements MarketplaceSimulationGateway {
    private final JdbcTemplate jdbc;

    public LocalMarketplaceSimulator(JdbcTemplate j) {
        jdbc = j;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Proof execute(Command c) {
        jdbc.update(
                "insert into marketplace_simulated_provider_receipts(operation_id,fingerprint,reference) values(?,?,?) on conflict(operation_id) do nothing",
                c.id(),
                fingerprint(c),
                "SIM-" + c.id());
        return verify(c);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Proof verify(Command c) {
        var rows =
                jdbc.query(
                        "select fingerprint,reference from marketplace_simulated_provider_receipts where operation_id=?",
                        (rs, n) -> {
                            if (!fingerprint(c).equals(rs.getString("fingerprint")))
                                throw new IllegalStateException("Simulation idempotency conflict");
                            return new Proof(
                                    c,
                                    "SUCCESS",
                                    rs.getString("reference"),
                                    c.recipientNet(),
                                    c.recipientNet().add(c.fee()));
                        },
                        c.id());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String fingerprint(Command c) {
        return ProviderCollectionLedger.accountReference(
                c.id()
                        + "|"
                        + c.organizationId()
                        + "|"
                        + c.collectionId()
                        + "|"
                        + c.kind()
                        + "|"
                        + c.sourceProvider()
                        + "|"
                        + c.sourceAccount()
                        + "|"
                        + c.currency()
                        + "|"
                        + c.recipientNet().toPlainString()
                        + "|"
                        + c.fee().toPlainString());
    }
}
