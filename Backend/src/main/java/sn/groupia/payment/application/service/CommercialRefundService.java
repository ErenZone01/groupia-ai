package sn.groupia.payment.application.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.organization.api.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.PaymentSettlementEvidence;
import sn.groupia.payment.api.event.CommercialRefundRecorded;
import sn.groupia.payment.domain.repository.*;
import sn.groupia.shared.api.ApiException;

@Service
public class CommercialRefundService implements CommercialRefunds {
    private final JdbcTemplate jdbc;
    private final FinancialStore store;
    private final RefundStore refunds;
    private final PaymentOperations payments;
    private final OrganizationAccess access;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public CommercialRefundService(
            JdbcTemplate j,
            FinancialStore s,
            RefundStore r,
            PaymentOperations p,
            OrganizationAccess a,
            ApplicationEventPublisher e,
            Clock c) {
        jdbc = j;
        store = s;
        refunds = r;
        payments = p;
        access = a;
        events = e;
        clock = c;
    }

    public record Declaration(
            long version, String externalMethod, String reference, String note, boolean confirmed) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void unknown(String key, Object value) {
            throw new IllegalArgumentException("Unsupported refund declaration field");
        }

        @Override
        public String toString() {
            return "Declaration[redacted]";
        }
    }

    public record RefundCaseDetail(
            CaseView refund,
            boolean canDeclare,
            boolean providerRefundAvailable,
            BigDecimal historicalFeeCompensationDue) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public CaseView open(
            UUID org,
            PaymentTarget target,
            UUID actor,
            String reason,
            UUID intention,
            long expectedVersion) {
        if (target.type() == PaymentTargetType.SUBSCRIPTION_INVOICE || actor == null)
            throw new IllegalArgumentException("Only commercial order cancellation is supported");
        reason = clean(reason, 500);
        if (reason == null)
            throw error(400, "CANCELLATION_REASON_REQUIRED", "Indiquez le motif d'annulation.");
        if (intention == null || expectedVersion < 0)
            throw error(
                    400,
                    "CANCELLATION_INTENT_REQUIRED",
                    "Confirmez l'intention et la version de la commande.");
        store.coordinate("commercial-cancellation:" + org + ":" + intention);
        refunds.coordinate("order:" + target.id());
        var old = find(org, target);
        if (old != null) {
            var intent =
                    jdbc.queryForMap(
                            "select cancellation_key,expected_order_version from commercial_refund_cases where id=?",
                            old.id());
            if (!intention.equals(intent.get("cancellation_key"))
                    || expectedVersion
                            != ((Number) intent.get("expected_order_version")).longValue()
                    || !reason.equals(old.reason())) throw conflict();
            return old;
        }
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists(select 1 from commercial_refund_cases where organization_id=? and cancellation_key=?)",
                        Boolean.class,
                        org,
                        intention))) throw conflict();
        var ids =
                jdbc.query(
                        "select id from payment_requests where organization_id=? and target_type=? and target_id=? order by id",
                        (rs, n) -> rs.getObject("id", UUID.class),
                        org,
                        target.type().name(),
                        target.id());
        if (ids.isEmpty())
            throw error(
                    409,
                    "CANCELLATION_PAYMENT_MISSING",
                    "L'obligation financière doit être vérifiée avant annulation.");
        var sources = new ArrayList<PaymentSettlementEvidence>();
        BigDecimal total = BigDecimal.ZERO, legacyFee = BigDecimal.ZERO;
        for (var id : ids) {
            var request = store.lockRequest(id);
            var state = payments.requestState(org, id);
            if (state.reconciliationRequired() || state.attemptInProgress()) throw uncertain();
            if (request.getSettledPaymentId() != null) {
                var proof = payments.settlementEvidence(org, id);
                if (proof.reversal()
                        || proof.status() != PaymentStatus.SUCCEEDED
                        || !proof.target().equals(target)
                        || !"XOF".equals(proof.currency())
                        || proof.providerAccountReference() == null
                        || proof.providerTransactionId() == null) throw uncertain();
                sources.add(proof);
                total = total.add(proof.amount());
                legacyFee = legacyFee.add(request.view().feeAmount());
            } else request.cancel(clock.instant());
        }
        store.flush();
        BigDecimal previous = BigDecimal.ZERO;
        if (target.type() == PaymentTargetType.ORDER) {
            for (var refund : refunds.history(org, target.id())) {
                if (refund.reconciliationRequired()
                        || List.of("CREATED", "PENDING", "REVIEW_REQUIRED")
                                .contains(refund.status())) throw uncertain();
                if (refund.succeeded()) {
                    if (!"XOF".equals(refund.currency())) throw uncertain();
                    previous = previous.add(refund.amount());
                }
            }
        }
        if (previous.compareTo(total) > 0) throw uncertain();
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        var due = total.subtract(previous);
        String status = due.signum() == 0 ? "NO_REFUND_DUE" : "REFUND_DUE";
        jdbc.update(
                "insert into commercial_refund_cases(id,organization_id,target_type,target_id,actor_id,reason,cancellation_key,expected_order_version,currency,collected_principal,previously_refunded,amount_due,legacy_platform_fee,status,opened_at) values(?,?,?,?,?,?,?,?,'XOF',?,?,?,?,?,?)",
                id,
                org,
                target.type().name(),
                target.id(),
                actor,
                reason,
                intention,
                expectedVersion,
                total,
                previous,
                due,
                legacyFee,
                status,
                Timestamp.from(now));
        for (var source : sources)
            jdbc.update(
                    "insert into commercial_refund_sources(case_id,payment_request_id,payment_id,provider,provider_account,provider_transaction_id,principal,currency) values(?,?,?,?,?,?,?,?)",
                    id,
                    source.paymentRequestId(),
                    source.paymentId(),
                    source.provider(),
                    source.providerAccountReference(),
                    source.providerTransactionId(),
                    source.amount(),
                    source.currency());
        audit(id, status, actor, now);
        return find(org, target);
    }

    @Transactional(readOnly = true)
    public CaseView find(UUID org, PaymentTarget target) {
        var rows =
                jdbc.query(
                        "select * from commercial_refund_cases where organization_id=? and target_type=? and target_id=?",
                        (rs, n) -> view(rs),
                        org,
                        target.type().name(),
                        target.id());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional(readOnly = true)
    public boolean externallyRecorded(UUID org, PaymentTarget target) {
        var found = find(org, target);
        return found != null && found.status().equals("EXTERNALLY_RECORDED");
    }

    @Transactional(readOnly = true)
    public List<UUID> sourceRequests(UUID org, UUID id) {
        caseById(org, id, false);
        return jdbc.queryForList(
                "select payment_request_id from commercial_refund_sources where case_id=? order by payment_request_id",
                UUID.class,
                id);
    }

    @Transactional(readOnly = true)
    public RefundCaseDetail detail(UUID org, UUID id) {
        access.requirePermission(org, OrganizationPermission.VIEW_PAYMENTS);
        var found = caseById(org, id, false);
        return new RefundCaseDetail(
                found,
                found.status().equals("REFUND_DUE")
                        && access.hasPermission(org, OrganizationPermission.MANAGE_REFUNDS),
                false,
                found.status().equals("EXTERNALLY_RECORDED")
                        ? found.legacyPlatformFee()
                        : BigDecimal.ZERO);
    }

    @Transactional
    public RefundCaseDetail declare(UUID org, UUID id, UUID key, Declaration declaration) {
        UUID actor = access.requireActorPermission(org, OrganizationPermission.MANAGE_REFUNDS);
        if (declaration == null || !declaration.confirmed() || key == null)
            throw error(
                    400,
                    "REFUND_CONFIRMATION_REQUIRED",
                    "Confirmez le remboursement déjà effectué hors Groupia.");
        var method = declaration.externalMethod();
        var reference = clean(declaration.reference(), 160);
        var note = clean(declaration.note(), 1000);
        if (method == null
                || !Set.of("CASH", "WAVE", "ORANGE_MONEY", "BANK_TRANSFER", "OTHER")
                        .contains(method))
            throw error(400, "REFUND_METHOD_INVALID", "Moyen de remboursement invalide.");
        if (method.equals("CASH") ? note == null : reference == null)
            throw error(
                    400,
                    "REFUND_EVIDENCE_REQUIRED",
                    "Indiquez la référence externe, ou une note pour les espèces.");
        var fingerprint =
                digest(
                        declaration.version()
                                + "|"
                                + method
                                + "|"
                                + fingerprintField(reference)
                                + fingerprintField(note));
        var found = caseById(org, id, true);
        var prior =
                jdbc.queryForMap(
                        "select declaration_key,declaration_fingerprint from commercial_refund_cases where id=?",
                        id);
        if (key.equals(prior.get("declaration_key"))) {
            if (!fingerprint.equals(prior.get("declaration_fingerprint"))) throw conflict();
            return detail(org, id);
        }
        if (!found.status().equals("REFUND_DUE") || found.version() != declaration.version())
            throw conflict();
        var sources =
                jdbc.query(
                        "select payment_request_id,payment_id,provider,provider_account,provider_transaction_id,principal,currency from commercial_refund_sources where case_id=? order by payment_request_id",
                        (rs, n) ->
                                new Source(
                                        rs.getObject("payment_request_id", UUID.class),
                                        rs.getObject("payment_id", UUID.class),
                                        rs.getString("provider"),
                                        rs.getString("provider_account"),
                                        rs.getString("provider_transaction_id"),
                                        rs.getBigDecimal("principal"),
                                        rs.getString("currency")),
                        id);
        for (var source : sources) {
            var state = payments.lockRequestState(org, source.request());
            var p = payments.settlementEvidence(org, source.request());
            if (state.reconciliationRequired()
                    || state.attemptInProgress()
                    || state.operationalStatus() != PaymentOperationalStatus.SETTLED
                    || p.reversal()
                    || !p.paymentId().equals(source.payment())
                    || !p.provider().equals(source.provider())
                    || !Objects.equals(p.providerAccountReference(), source.account())
                    || !Objects.equals(p.providerTransactionId(), source.transaction())
                    || !p.currency().equals(source.currency())
                    || p.amount().compareTo(source.amount()) != 0) throw uncertain();
        }
        // A reference can acknowledge one external refund only; never infer provider success.
        jdbc.queryForObject(
                "select count(*) from (select pg_advisory_xact_lock(hashtextextended(?,0))) l",
                Long.class,
                "commercial-refund-declaration:" + org);
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists(select 1 from commercial_refund_cases where organization_id=? and (declaration_key=? or (external_method=? and external_reference=?)))",
                        Boolean.class,
                        org,
                        key,
                        method,
                        reference))) throw conflict();
        Instant now = clock.instant();
        jdbc.update(
                "update commercial_refund_cases set status='EXTERNALLY_RECORDED',externally_recorded_at=?,recorded_by=?,declaration_key=?,declaration_fingerprint=?,external_method=?,external_reference=?,note=?,version=version+1 where id=?",
                Timestamp.from(now),
                actor,
                key,
                fingerprint,
                method,
                reference,
                note,
                id);
        UUID event = audit(id, "EXTERNALLY_RECORDED", actor, now);
        events.publishEvent(
                new CommercialRefundRecorded(
                        event, now, org, found.target(), id, found.amountDue(), found.currency()));
        return detail(org, id);
    }

    private record Source(
            UUID request,
            UUID payment,
            String provider,
            String account,
            String transaction,
            BigDecimal amount,
            String currency) {}

    private CaseView caseById(UUID org, UUID id, boolean lock) {
        var rows =
                jdbc.query(
                        "select * from commercial_refund_cases where organization_id=? and id=?"
                                + (lock ? " for update" : ""),
                        (rs, n) -> view(rs),
                        org,
                        id);
        if (rows.isEmpty()) throw error(404, "COMMERCIAL_REFUND_NOT_FOUND", "Dossier introuvable.");
        return rows.getFirst();
    }

    private UUID audit(UUID id, String status, UUID actor, Instant now) {
        UUID event = UUID.randomUUID();
        jdbc.update(
                "insert into commercial_refund_events(id,case_id,status,actor_id,occurred_at) values(?,?,?,?,?)",
                event,
                id,
                status,
                actor,
                Timestamp.from(now));
        return event;
    }

    private static CaseView view(ResultSet rs) throws SQLException {
        var reference = rs.getString("external_reference");
        var recorded = rs.getTimestamp("externally_recorded_at");
        return new CaseView(
                rs.getObject("id", UUID.class),
                new PaymentTarget(
                        PaymentTargetType.valueOf(rs.getString("target_type")),
                        rs.getObject("target_id", UUID.class)),
                rs.getString("status"),
                rs.getString("currency"),
                rs.getBigDecimal("collected_principal"),
                rs.getBigDecimal("previously_refunded"),
                rs.getBigDecimal("amount_due"),
                rs.getBigDecimal("legacy_platform_fee"),
                rs.getString("reason"),
                rs.getTimestamp("opened_at").toInstant(),
                recorded == null ? null : recorded.toInstant(),
                rs.getString("external_method"),
                reference == null
                        ? null
                        : "…" + reference.substring(Math.max(0, reference.length() - 4)),
                rs.getLong("version"));
    }

    private static String clean(String s, int max) {
        if (s == null || s.isBlank()) return null;
        s = s.strip();
        if (s.length() > max || s.codePoints().anyMatch(Character::isISOControl))
            throw error(400, "REFUND_EVIDENCE_INVALID", "Référence, motif ou note invalide.");
        return s;
    }

    private static String fingerprintField(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    private static String digest(String s) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException uncertain() {
        return error(
                409,
                "CANCELLATION_FINANCIAL_REVIEW",
                "Le paiement ou un remboursement est en cours de vérification. Aucun remboursement supplémentaire ne peut être enregistré.");
    }

    private static ApiException conflict() {
        return error(
                409,
                "COMMERCIAL_REFUND_CONFLICT",
                "Ce dossier ou cette déclaration a changé. Rechargez son état.");
    }

    private static ApiException error(int status, String code, String message) {
        return new ApiException(status, code, message);
    }
}
