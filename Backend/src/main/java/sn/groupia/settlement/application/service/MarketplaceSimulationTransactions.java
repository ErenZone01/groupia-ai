package sn.groupia.settlement.application.service;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.organization.api.*;
import sn.groupia.payment.api.*;
import sn.groupia.settlement.api.dto.MarketplaceSimulationView;
import sn.groupia.settlement.domain.service.MarketplaceSimulationGateway.*;
import sn.groupia.shared.api.ApiException;

@Service
public class MarketplaceSimulationTransactions {
    private final JdbcTemplate jdbc;
    private final OrganizationAccess access;
    private final PaymentOperations payments;
    private final Clock clock;
    private final boolean enabled;
    private final BigDecimal fee, budget;

    public MarketplaceSimulationTransactions(
            JdbcTemplate j,
            OrganizationAccess a,
            PaymentOperations p,
            Clock c,
            @Value("${groupia.marketplace.simulation.enabled:false}") boolean e,
            @Value("${groupia.marketplace.simulation.fee-xof:200}") BigDecimal f,
            @Value("${groupia.marketplace.simulation.company-budget-xof:100000}") BigDecimal b) {
        jdbc = j;
        access = a;
        payments = p;
        clock = c;
        enabled = e;
        fee = whole(f);
        budget = whole(b);
    }

    public record SimulationQuote(
            String mode,
            boolean enabled,
            String kind,
            UUID collectionId,
            String currency,
            BigDecimal recipientNet,
            BigDecimal fee,
            BigDecimal simulatedCompanyFunding,
            String reference,
            boolean realOperationsEnabled) {}

    public record SimulationCommand(String kind, String quoteReference, boolean confirmed) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void unknown(String key, Object value) {
            throw new IllegalArgumentException("Unsupported simulation field");
        }
    }

    private record Source(
            UUID id,
            UUID request,
            String provider,
            String account,
            String currency,
            BigDecimal seller,
            BigDecimal principal) {}

    private static BigDecimal whole(BigDecimal n) {
        if (n == null || n.signum() < 0)
            throw new IllegalArgumentException("Invalid simulation funding");
        return n.setScale(0, java.math.RoundingMode.UNNECESSARY);
    }

    private OrganizationPermission permission(String kind) {
        return switch (kind == null ? "" : kind) {
            case "PAYOUT" -> OrganizationPermission.EXECUTE_PAYOUTS;
            case "REFUND" -> OrganizationPermission.MANAGE_REFUNDS;
            default ->
                    throw new ApiException(400, "SIMULATION_KIND_INVALID", "Opération inconnue.");
        };
    }

    @Transactional(readOnly = true)
    public SimulationQuote quote(UUID org, UUID collection, String kind) {
        access.requirePermission(org, permission(kind));
        return quote(source(org, collection), kind);
    }

    private SimulationQuote quote(Source s, String kind) {
        var amount = kind.equals("PAYOUT") ? s.seller() : s.principal();
        // Refund simulation covers the collected principal. No undocumented pay-in fee restitution.
        var hash =
                ProviderCollectionLedger.accountReference(
                        "SIMULATION_V1|"
                                + kind
                                + "|"
                                + s.id()
                                + "|"
                                + s.provider()
                                + "|"
                                + s.account()
                                + "|"
                                + s.currency()
                                + "|"
                                + amount.toPlainString()
                                + "|"
                                + fee.toPlainString());
        return new SimulationQuote(
                "SIMULATION", enabled, kind, s.id(), s.currency(), amount, fee, fee, hash, false);
    }

    @Transactional
    public Command reserve(UUID org, UUID collection, UUID key, SimulationCommand command) {
        if (command == null)
            throw new ApiException(
                    400, "SIMULATION_CONFIRMATION_REQUIRED", "Confirmez la simulation.");
        UUID actor = access.requireActorPermission(org, permission(command.kind()));
        if (!enabled)
            throw new ApiException(409, "SIMULATION_DISABLED", "Les simulations sont désactivées.");
        if (!command.confirmed() || key == null)
            throw new ApiException(
                    400, "SIMULATION_CONFIRMATION_REQUIRED", "Confirmez la simulation.");
        // Transaction-local organization lock serializes the synthetic fee budget and retries.
        jdbc.queryForObject(
                "select count(*) from (select pg_advisory_xact_lock(hashtextextended(?,0))) l",
                Long.class,
                "marketplace-simulation:" + org);
        var previous =
                jdbc.query(
                        "select * from marketplace_simulations where organization_id=? and idempotency_key=?",
                        (rs, n) -> {
                            if (!rs.getObject("collection_id", UUID.class).equals(collection)
                                    || !rs.getString("kind").equals(command.kind())
                                    || !rs.getString("quote_reference")
                                            .equals(command.quoteReference()))
                                throw new ApiException(
                                        409,
                                        "SIMULATION_INTENT_CONFLICT",
                                        "Cette intention correspond à une autre simulation.");
                            return gatewayCommand(rs);
                        },
                        org,
                        key);
        if (!previous.isEmpty()) return previous.getFirst();
        var s = source(org, collection);
        var q = quote(s, command.kind());
        if (!q.reference().equals(command.quoteReference()) || q.recipientNet().signum() <= 0)
            throw new ApiException(
                    409, "SIMULATION_QUOTE_CHANGED", "Actualisez le devis de simulation.");
        var state = payments.lockRequestState(org, s.request());
        var proof = payments.settlementEvidence(org, s.request());
        if (state.reconciliationRequired()
                || state.attemptInProgress()
                || state.operationalStatus() != PaymentOperationalStatus.SETTLED
                || proof.reversal()
                || !proof.provider().equals(s.provider())
                || !Objects.equals(proof.providerAccountReference(), s.account())
                || !proof.currency().equals(s.currency())
                || proof.amount().compareTo(s.principal()) != 0)
            throw new ApiException(
                    409,
                    "SIMULATION_SOURCE_UNCERTAIN",
                    "Le règlement source nécessite une vérification.");
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists(select 1 from marketplace_simulations where organization_id=? and collection_id=? and status<>'FAILED')",
                        Boolean.class,
                        org,
                        collection)))
            throw new ApiException(
                    409,
                    "SIMULATION_SOURCE_RESERVED",
                    "Cet encaissement est déjà utilisé dans une simulation.");
        var used =
                jdbc.queryForObject(
                        "select coalesce(sum(fee),0) from marketplace_simulations where organization_id=? and status<>'FAILED'",
                        BigDecimal.class,
                        org);
        if (used.add(fee).compareTo(budget) > 0)
            throw new ApiException(
                    409,
                    "SIMULATION_FEE_FUNDING_REQUIRED",
                    "Le budget fictif doit couvrir les frais sans réduire le net.");
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update(
                "insert into marketplace_simulations(id,organization_id,collection_id,actor_id,idempotency_key,kind,source_provider,source_account,currency,recipient_net,fee,quote_reference,status,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,'CREATED',?)",
                id,
                org,
                collection,
                actor,
                key,
                command.kind(),
                s.provider(),
                s.account(),
                s.currency(),
                q.recipientNet(),
                fee,
                q.reference(),
                Timestamp.from(now));
        event(id, "CREATED", now);
        return new Command(
                id,
                org,
                collection,
                command.kind(),
                s.provider(),
                s.account(),
                s.currency(),
                q.recipientNet(),
                fee);
    }

    @Transactional(readOnly = true)
    public Command command(UUID org, UUID id) {
        access.requirePermission(org, OrganizationPermission.VIEW_PAYMENTS);
        var rows =
                jdbc.query(
                        "select * from marketplace_simulations where organization_id=? and id=?",
                        (rs, n) -> gatewayCommand(rs),
                        org,
                        id);
        if (rows.isEmpty()) throw missing();
        var c = rows.getFirst();
        access.requirePermission(org, permission(c.kind()));
        return c;
    }

    @Transactional(readOnly = true)
    public List<MarketplaceSimulationView> history(UUID org, UUID collection) {
        access.requirePermission(org, OrganizationPermission.VIEW_PAYMENTS);
        source(org, collection);
        return jdbc.query(
                "select * from marketplace_simulations where organization_id=? and collection_id=? order by created_at,id",
                (rs, n) -> view(rs),
                org,
                collection);
    }

    @Transactional(readOnly = true)
    public MarketplaceSimulationView get(UUID org, UUID id) {
        access.requirePermission(org, OrganizationPermission.VIEW_PAYMENTS);
        var rows =
                jdbc.query(
                        "select * from marketplace_simulations where organization_id=? and id=?",
                        (rs, n) -> view(rs),
                        org,
                        id);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    @Transactional
    public MarketplaceSimulationView finish(Command c, Proof proof) {
        var row =
                jdbc.query(
                        "select * from marketplace_simulations where organization_id=? and id=? for update",
                        (rs, n) -> view(rs),
                        c.organizationId(),
                        c.id());
        if (row.isEmpty()) throw missing();
        var stored =
                jdbc.queryForObject(
                        "select * from marketplace_simulations where organization_id=? and id=?",
                        (rs, n) -> gatewayCommand(rs),
                        c.organizationId(),
                        c.id());
        if (!same(c, stored))
            throw new IllegalStateException("Simulation persisted command mismatch");
        var current = row.getFirst();
        if (List.of("SUCCEEDED", "FAILED").contains(current.status())) return current;
        String status = "REVIEW_REQUIRED", reference = null;
        if (proof != null
                && same(c, proof.command())
                && proof.reference() != null
                && proof.reference().equals("SIM-" + c.id())
                && proof.recipientNet() != null
                && proof.recipientNet().compareTo(c.recipientNet()) == 0
                && proof.debited() != null
                && proof.debited().compareTo(c.recipientNet().add(c.fee())) == 0) {
            status =
                    switch (proof.status() == null ? "" : proof.status()) {
                        case "SUCCESS" -> "SUCCEEDED";
                        case "FAILED" -> "FAILED";
                        default -> "REVIEW_REQUIRED";
                    };
            reference = proof.reference();
        }
        Instant now = clock.instant();
        boolean terminal = List.of("SUCCEEDED", "FAILED").contains(status);
        jdbc.update(
                "update marketplace_simulations set status=?,provider_reference=?,completed_at=? where id=?",
                status,
                reference,
                terminal ? Timestamp.from(now) : null,
                c.id());
        event(c.id(), status, now);
        return get(c.organizationId(), c.id());
    }

    private static boolean same(Command a, Command b) {
        return b != null
                && b.recipientNet() != null
                && b.fee() != null
                && a.id().equals(b.id())
                && a.organizationId().equals(b.organizationId())
                && a.collectionId().equals(b.collectionId())
                && a.kind().equals(b.kind())
                && a.sourceProvider().equals(b.sourceProvider())
                && a.sourceAccount().equals(b.sourceAccount())
                && a.currency().equals(b.currency())
                && a.recipientNet().compareTo(b.recipientNet()) == 0
                && a.fee().compareTo(b.fee()) == 0;
    }

    private void event(UUID id, String status, Instant now) {
        jdbc.update(
                "insert into marketplace_simulation_events(id,operation_id,status,occurred_at) values(?,?,?,?) on conflict(operation_id,status) do nothing",
                UUID.randomUUID(),
                id,
                status,
                Timestamp.from(now));
    }

    private Source source(UUID org, UUID id) {
        var rows =
                jdbc.query(
                        "select * from provider_collections where organization_id=? and id=?",
                        (rs, n) ->
                                new Source(
                                        id,
                                        rs.getObject("payment_request_id", UUID.class),
                                        rs.getString("provider"),
                                        rs.getString("provider_account"),
                                        rs.getString("currency"),
                                        rs.getBigDecimal("seller_principal"),
                                        rs.getBigDecimal("principal")),
                        org,
                        id);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    private static Command gatewayCommand(ResultSet rs) throws SQLException {
        return new Command(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("collection_id", UUID.class),
                rs.getString("kind"),
                rs.getString("source_provider"),
                rs.getString("source_account"),
                rs.getString("currency"),
                rs.getBigDecimal("recipient_net"),
                rs.getBigDecimal("fee"));
    }

    private static MarketplaceSimulationView view(ResultSet rs) throws SQLException {
        return new MarketplaceSimulationView(
                rs.getObject("id", UUID.class),
                rs.getObject("collection_id", UUID.class),
                "SIMULATION",
                rs.getString("kind"),
                rs.getString("status"),
                rs.getString("source_provider"),
                ProviderCollectionLedger.accountReference(rs.getString("source_account")),
                rs.getString("currency"),
                rs.getBigDecimal("recipient_net"),
                rs.getBigDecimal("fee"),
                rs.getBigDecimal("fee"),
                rs.getString("provider_reference"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("completed_at") == null
                        ? null
                        : rs.getTimestamp("completed_at").toInstant());
    }

    private static ApiException missing() {
        return new ApiException(
                404, "SIMULATION_NOT_FOUND", "Simulation ou encaissement introuvable.");
    }
}
