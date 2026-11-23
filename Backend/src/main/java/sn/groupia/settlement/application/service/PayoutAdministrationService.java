package sn.groupia.settlement.application.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.organization.api.*;
import sn.groupia.settlement.domain.model.PayoutAdministrativeApproval;
import sn.groupia.settlement.domain.repository.MerchantFinanceStore;
import sn.groupia.settlement.domain.service.MerchantPayoutProvider;
import sn.groupia.settlement.infrastructure.ReviewedPayoutEvidence;
import sn.groupia.shared.api.ApiException;

/** Administrative decisions only: deliberately has no provider initiate/verify call. */
@Service
@Transactional
public class PayoutAdministrationService {
    private final MerchantFinanceStore store;
    private final OrganizationAccess access;
    private final ReviewedPayoutEvidence evidence;
    private final DirectMerchantBalances direct;
    private final MerchantBalances balances;
    private final MerchantPayoutTransactions policy;
    private final MerchantPayoutProvider provider;
    private final Clock clock;

    @Value("${groupia.settlement.payout-administration.enabled:false}")
    private boolean enabled;

    @Value("${groupia.settlement.payout-administration.operator-ids:}")
    private String operators;

    public PayoutAdministrationService(
            MerchantFinanceStore store,
            OrganizationAccess access,
            ReviewedPayoutEvidence evidence,
            DirectMerchantBalances direct,
            MerchantBalances balances,
            MerchantPayoutTransactions policy,
            MerchantPayoutProvider provider,
            Clock clock) {
        this.store = store;
        this.access = access;
        this.evidence = evidence;
        this.direct = direct;
        this.balances = balances;
        this.policy = policy;
        this.provider = provider;
        this.clock = clock;
    }

    public record Result(UUID payoutId, String status, UUID authorizationId) {}

    public Result decide(
            UUID org,
            UUID id,
            String action,
            String reference,
            long version,
            String reason,
            boolean confirmed) {
        UUID actor = requireOperator(org);
        if (!confirmed
                || action == null
                || !Set.of("CLOSE_NON_EXECUTED", "INDEPENDENT_PILOT").contains(action)
                || reference == null
                || !reference.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")
                || reason == null
                || reason.strip().length() < 10
                || reason.length() > 500
                || reason.codePoints().anyMatch(Character::isISOControl))
            throw error(
                    400,
                    "PAYOUT_ADMINISTRATION_INVALID",
                    "Confirmation, référence et motif explicite requis.");
        reason = reason.strip();
        var verified = evidence.read(reference);
        var a = verified.approval();
        var now = clock.instant();
        store.lockOrganization(org);
        var p = store.payout(org, id);
        if (!org.equals(a.organizationId())
                || !id.equals(a.payoutId())
                || !action.equals(a.action())
                || !actor.equals(a.operatorId())
                || version != a.payoutVersion()
                || !"SASPAY".equals(p.provider())
                || !p.providerAccountReference().equals(a.providerAccount())
                || !ReviewedPayoutEvidence.sha256(p.providerIdempotencyKey().toString())
                        .equals(a.originalKeySha256())
                || !ReviewedPayoutEvidence.sha256(p.businessReference())
                        .equals(a.businessReferenceSha256())
                || a.amount() == null
                || p.amount().compareTo(a.amount()) != 0
                || !p.currency().equals(a.currency())
                || a.evidenceReference() == null
                || !a.evidenceReference().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"))
            throw error(
                    409,
                    "PAYOUT_EVIDENCE_SCOPE_MISMATCH",
                    "La preuve ne correspond pas exactement à cette intention.");
        var replay =
                store.administrativeReplay(
                        id, action, reference, verified.digest(), reason, actor, version);
        if (replay.isPresent()) return new Result(id, p.status(), replay.get());
        if (p.version() != version
                || !"REVIEW_REQUIRED".equals(p.status())
                || !p.reconciliationRequired()
                || p.providerTransactionId() != null
                || !p.directSale()
                || !p.reserved())
            throw error(
                    409,
                    "PAYOUT_RESOLUTION_STATE_CHANGED",
                    "État du retrait incompatible ou modifié ; nouvelle revue requise.");
        if (a.reviewedAt() == null
                || a.expiresAt() == null
                || a.reviewedAt().isBefore(p.requestedAt())
                || a.reviewedAt().isAfter(now)
                || !a.expiresAt().isAfter(now)
                || a.expiresAt().isAfter(a.reviewedAt().plus(Duration.ofHours(24))))
            throw error(409, "PAYOUT_APPROVAL_EXPIRED", "Autorisation absente, future ou expirée.");
        if ("CLOSE_NON_EXECUTED".equals(action)) {
            // Historical HTTP 403 responses were previously held as uncertain even
            // though the provider gave a negative acknowledgement. Accept a reviewed,
            // pinned source-response dossier for these cases only; unrelated timeouts
            // and historical manual recoveries still require independent proof.
            boolean reviewedRejection =
                    "PROVIDER_HTTP_REJECTION".equals(a.evidenceType())
                            && "SASPAY_PAYOUT_HTTP_REJECTED".equals(p.reconciliationReason());
            if (a.evidenceType() == null
                    || (!Set.of("PROVIDER_ATTESTATION", "COMPLETE_PRE_DISPATCH_AUDIT")
                                    .contains(a.evidenceType())
                            && !reviewedRejection)
                    || !a.allAttemptsCovered()
                    || !a.definitiveNonExecution()
                    || !a.futureExecutionImpossible())
                throw error(
                        409,
                        "PAYOUT_NON_EXECUTION_NOT_PROVEN",
                        "Non-exécution définitive de toutes les tentatives non prouvée.");
            UUID audit = store.recordAdministration(a, verified.digest(), reason, now);
            store.state(id, "NON_EXECUTE", null, null, null, false, null);
            return new Result(id, "NON_EXECUTE", audit);
        }
        if (!"INDEPENDENT_FUNDS_RISK_REVIEW".equals(a.evidenceType())
                || a.directOrderId() == null
                || a.directOrderId().equals(p.directOrderId())
                || a.destinationId() == null
                || !a.destinationId().equals(p.destinationId())
                || a.amount().compareTo(new BigDecimal("1000")) != 0
                || !"SASPAY".equals(provider.name())
                || !provider.account().equals(a.providerAccount())
                || store.hasResolutionIncident(org)
                || !provider.administrativeApprovalEligible())
            throw error(
                    409,
                    "PAYOUT_INDEPENDENT_PILOT_INELIGIBLE",
                    "Ce pilote indépendant n'est pas éligible.");
        policy.requireSaspayPilot(org, a.amount());
        policy.requireAdministrativePilotTarget(a.directOrderId(), "WAVE");
        var destination =
                store.destination(org)
                        .orElseThrow(
                                () ->
                                        error(
                                                409,
                                                "PAYOUT_DESTINATION_REQUIRED",
                                                "Destination absente."));
        if (!destination.id().equals(a.destinationId()) || !"WAVE".equals(destination.method()))
            throw error(
                    409, "PAYOUT_DESTINATION_CHANGED", "La destination Wave approuvée a changé.");
        if (store.payouts(org).stream()
                .anyMatch(other -> other.reserved() && !other.id().equals(id)))
            throw error(
                    409, "PAYOUT_OTHER_INTENT_UNRESOLVED", "Une autre demande non résolue existe.");
        direct.lock(org, a.directOrderId());
        var target =
                direct.calculateForApproval(org).stream()
                        .filter(r -> r.directOrderId().equals(a.directOrderId()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        error(
                                                409,
                                                "MERCHANT_FUNDS_NOT_AVAILABLE",
                                                "Fonds indépendants absents."));
        var global = balances.calculateForApproval(org);
        if (target.availableForPayout().compareTo(a.amount()) < 0
                || target.deficit().signum() != 0
                || global.availableForPayout().compareTo(a.amount()) < 0
                || global.deficit().signum() != 0
                || global.reservedForPayout().compareTo(p.amount()) < 0)
            throw error(
                    409,
                    "MERCHANT_FUNDS_NOT_AVAILABLE",
                    "Fonds indépendants et réserve historique insuffisamment couverts.");
        UUID audit = store.recordAdministration(a, verified.digest(), reason, now);
        return new Result(id, p.status(), audit);
    }

    /**
     * Narrow administrative path for historical explicit SasPay authorization refusals. Unlike an
     * uncertain POST, the gateway supplied a negative acknowledgment. The operator attests to
     * complete source-log review; the immutable journal records that decision. No provider POST or
     * replay is invoked here.
     */
    public Result closeRecordedRejection(
            UUID org,
            UUID id,
            long expectedVersion,
            String providerCode,
            String evidenceReference,
            String reason,
            boolean allAttemptsReviewed,
            boolean definitiveNonExecution,
            boolean futureExecutionImpossible,
            boolean confirmed) {
        UUID actor = requireOperator(org);
        if (!confirmed
                || !allAttemptsReviewed
                || !definitiveNonExecution
                || !futureExecutionImpossible
                || expectedVersion < 0
                || !"payout_not_enabled".equals(providerCode)
                || evidenceReference == null
                || !evidenceReference.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")
                || reason == null
                || reason.strip().length() < 20
                || reason.length() > 500
                || reason.codePoints().anyMatch(Character::isISOControl))
            throw error(
                    400,
                    "PAYOUT_REJECTION_REVIEW_INCOMPLETE",
                    "Revue exhaustive et référence vérifiable du refus HTTP 403 requises.");

        reason = reason.strip();
        var now = clock.instant();
        store.lockOrganization(org);
        var payout = store.payout(org, id);
        String reference = "RECORDED-HTTP403-" + id;
        String evidenceDigest =
                ReviewedPayoutEvidence.sha256(
                        String.join(
                                "|",
                                "SASPAY_HTTP403",
                                providerCode,
                                evidenceReference,
                                reason,
                                org.toString(),
                                id.toString(),
                                Long.toString(expectedVersion),
                                actor.toString()));
        var replay =
                store.administrativeReplay(
                        id,
                        "CLOSE_NON_EXECUTED",
                        reference,
                        evidenceDigest,
                        reason,
                        actor,
                        expectedVersion);
        if (replay.isPresent()) return new Result(id, payout.status(), replay.get());
        if (!"SASPAY".equals(payout.provider())
                || !provider.name().equals(payout.provider())
                || !provider.account().equals(payout.providerAccountReference())
                || !payout.directSale()
                || !"REVIEW_REQUIRED".equals(payout.status())
                || !payout.reconciliationRequired()
                || !"SASPAY_PAYOUT_HTTP_REJECTED".equals(payout.reconciliationReason())
                || payout.providerTransactionId() != null
                || !payout.reserved()
                || store.hasResolutionIncident(org)
                || payout.version() != expectedVersion)
            throw error(
                    409,
                    "PAYOUT_RECORDED_REJECTION_INELIGIBLE",
                    "Cette intention ne possède pas le refus fournisseur confirmé requis.");

        var approval =
                new PayoutAdministrativeApproval(
                        reference,
                        "CLOSE_NON_EXECUTED",
                        org,
                        id,
                        expectedVersion,
                        actor,
                        payout.providerAccountReference(),
                        ReviewedPayoutEvidence.sha256(payout.providerIdempotencyKey().toString()),
                        ReviewedPayoutEvidence.sha256(payout.businessReference()),
                        payout.amount(),
                        payout.currency(),
                        "PROVIDER_HTTP_REJECTION",
                        evidenceReference,
                        evidenceDigest,
                        true,
                        true,
                        true,
                        now,
                        now.plus(Duration.ofHours(1)),
                        null,
                        null);
        UUID audit = store.recordAdministration(approval, evidenceDigest, reason, now);
        store.state(id, "NON_EXECUTE", null, null, null, false, null);
        return new Result(id, "NON_EXECUTE", audit);
    }

    /** Read-only compatibility view of the retired owner-risk exception. */
    public record HistoricalUnlinkedStatus(
            UUID payoutId,
            UUID directOrderId,
            String status,
            String reconciliationReason,
            long version,
            boolean eligible) {}

    public HistoricalUnlinkedStatus historicalUnlinkedStatus(UUID org, UUID id) {
        requireOperator(org);
        var payout = store.payout(org, id);
        return new HistoricalUnlinkedStatus(
                id,
                payout.directOrderId(),
                payout.status(),
                payout.reconciliationReason(),
                payout.version(),
                false);
    }

    public Result closeHistoricalUnlinked(
            UUID org,
            UUID id,
            long expectedVersion,
            String evidenceReference,
            String reason,
            boolean ownerConfirmsNeverExecuted,
            boolean acceptsDuplicatePayoutRisk,
            boolean confirmed) {
        requireOperator(org);
        store.payout(org, id); // Retain organization scoping before exposing endpoint status.
        // An owner assertion and an empty provider list do not prove non-execution.
        // Historical decisions remain immutable; no reserve or audit record is rewritten here.
        throw error(
                410,
                "PAYOUT_HISTORICAL_REVIEW_RETIRED",
                "La réserve nécessite une preuve indépendante de non-exécution.");
    }

    private UUID requireOperator(UUID org) {
        UUID actor = access.requireActorPermission(org, OrganizationPermission.EXECUTE_PAYOUTS);
        access.requirePermission(org, OrganizationPermission.MANAGE_SETTLEMENTS);
        if (!enabled
                || operators == null
                || Arrays.stream(operators.split(","))
                        .map(String::strip)
                        .noneMatch(actor.toString()::equals))
            throw error(
                    403,
                    "PAYOUT_ADMINISTRATION_FORBIDDEN",
                    "Opération réservée à un opérateur explicitement autorisé.");
        return actor;
    }

    private static ApiException error(int status, String code, String message) {
        return new ApiException(status, code, message);
    }
}
