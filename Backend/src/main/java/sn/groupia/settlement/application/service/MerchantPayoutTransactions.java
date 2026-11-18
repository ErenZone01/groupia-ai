package sn.groupia.settlement.application.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.grouping.api.MerchantCampaignFacts;
import sn.groupia.organization.api.*;
import sn.groupia.payment.api.OutgoingPayoutOperations;
import sn.groupia.payment.api.OutgoingPayoutOperations.Kind;
import sn.groupia.settlement.domain.model.*;
import sn.groupia.settlement.domain.repository.MerchantFinanceStore;
import sn.groupia.settlement.domain.service.MerchantPayoutProvider;
import sn.groupia.settlement.domain.service.PayoutProviderFailure;
import sn.groupia.shared.api.ApiException;

@Service
@Transactional
public class MerchantPayoutTransactions {
    private final OutgoingPayoutOperations outgoing;
    private final MerchantFinanceStore store;
    private final MerchantBalances balances;
    private final DirectMerchantBalances direct;
    private final MerchantCampaignFacts campaigns;
    private final OrganizationAccess access;
    private final MerchantPayoutProvider provider;
    private final Clock clock;
    private final boolean enabled;
    private final boolean directPayoutsEnabled;

    // LIVE pilot remains fail-closed: only one approved organization, <= 1000 XOF,
    // and no unresolved concurrent intent without a durable, independently reviewed grant.
    @Value("${groupia.settlement.saspay-payout-pilot.enabled:false}")
    private boolean saspayPayoutPilotEnabled;

    // Normal seller withdrawals; temporary one-order/1000 XOF pilot does not apply.
    @Value("${groupia.settlement.saspay-payout-general.enabled:false}")
    private boolean saspayGeneralPayoutsEnabled;

    // Permanent server-side risk limits, independent of the removed one-order pilot.
    @Value("${groupia.settlement.saspay-payout-general.max-per-request-xof:100000}")
    private BigDecimal saspayPayoutGeneralMaxXof = new BigDecimal("100000");

    @Value("${groupia.settlement.saspay-payout-general.daily-max-xof:500000}")
    private BigDecimal saspayPayoutGeneralDailyMaxXof = new BigDecimal("500000");

    @Value("${groupia.settlement.saspay-payout-pilot.organization-id:}")
    private String saspayPayoutPilotOrganizationId;

    @Value("${groupia.settlement.saspay-payout-pilot.max-xof:1000}")
    private BigDecimal saspayPayoutPilotMaxXof;

    @Value("${groupia.settlement.saspay-payout-live-pilot.enabled:false}")
    private boolean saspayLivePilotReleased;

    @Value("${groupia.settlement.saspay-payout-live-pilot.direct-order-id:}")
    private String saspayLivePilotDirectOrderId;

    // One-time manual recovery of a specific already-reserved payout.
    // The original provider idempotency key and the immutable beneficiary are reused.
    @Value("${groupia.settlement.saspay-payout-recovery.enabled:false}")
    private boolean saspayPayoutRecoveryEnabled;

    @Value("${groupia.settlement.saspay-payout-recovery.payout-id:}")
    private String saspayPayoutRecoveryId;

    @Value("${groupia.settlement.saspay-payout-recovery.original-provider-key:}")
    private String saspayPayoutRecoveryOriginalKey;

    MerchantPayoutTransactions(
            MerchantFinanceStore s,
            MerchantBalances b,
            DirectMerchantBalances direct,
            MerchantCampaignFacts c,
            OrganizationAccess a,
            MerchantPayoutProvider p,
            Clock clock,
            OutgoingPayoutOperations outgoing,
            @Value("${groupia.settlement.payouts.enabled:false}") boolean enabled,
            @Value("${groupia.settlement.direct-payouts.enabled:false}")
                    boolean directPayoutsEnabled) {
        this.outgoing = outgoing;
        store = s;
        balances = b;
        this.direct = direct;
        campaigns = c;
        access = a;
        provider = p;
        this.clock = clock;
        this.enabled = enabled;
        this.directPayoutsEnabled = directPayoutsEnabled;
    }

    public boolean available() {
        return enabled && provider.enabled();
    }

    public boolean verificationAvailable() {
        return "SASPAY".equals(provider.name()) ? provider.verificationEnabled() : available();
    }

    void requireLivePilotTarget(UUID directOrderId, String payoutMethod) {
        if (!"SASPAY".equals(provider.name())
                || saspayGeneralPayoutsEnabled
                || !saspayLivePilotReleased) return;
        requireAdministrativePilotTarget(directOrderId, payoutMethod);
    }

    /** The configured scope remains mandatory while dispatch is closed. */
    void requireAdministrativePilotTarget(UUID directOrderId, String payoutMethod) {
        if (directOrderId == null
                || !directOrderId.toString().equals(saspayLivePilotDirectOrderId)
                || (payoutMethod != null && !"WAVE".equals(payoutMethod)))
            throw error(
                    403,
                    "SASPAY_PAYOUT_LIVE_PILOT_TARGET_MISMATCH",
                    "Le pilote LIVE est réservé à une vente directe et à une destination Wave.");
    }

    private MerchantPayoutFeeQuote requireQuoteForAmount(UUID org, UUID quoteId) {
        if (quoteId == null)
            throw error(409, "PAYOUT_FEE_QUOTE_REQUIRED", "Devis de retrait obligatoire.");
        return store.feeQuote(org, quoteId);
    }

    void requireSaspayPilot(UUID org, BigDecimal gross) {
        if (!"SASPAY".equals(provider.name())) return;
        if (saspayGeneralPayoutsEnabled) {
            if (!validGeneralRiskLimits()
                    || gross == null
                    || gross.signum() <= 0
                    || gross.stripTrailingZeros().scale() > 0
                    || gross.compareTo(saspayPayoutGeneralMaxXof) > 0)
                throw error(
                        409,
                        "SASPAY_PAYOUT_PRODUCTION_LIMIT",
                        "Le montant dépasse la limite de versement autorisée.");
            return;
        }
        if (!saspayPayoutPilotEnabled
                || saspayPayoutPilotMaxXof == null
                || saspayPayoutPilotMaxXof.signum() <= 0
                || saspayPayoutPilotMaxXof.compareTo(new BigDecimal("1000")) > 0)
            throw error(503, "SASPAY_PAYOUT_PILOT_LOCKED", "Pilote SasPay non autorisé.");
        if (!org.toString().equals(saspayPayoutPilotOrganizationId))
            throw error(
                    403,
                    "SASPAY_PAYOUT_PILOT_ORGANIZATION",
                    "Boutique non autorisée pour le pilote.");
        if (gross == null
                || gross.signum() <= 0
                || gross.stripTrailingZeros().scale() > 0
                || gross.compareTo(saspayPayoutPilotMaxXof) > 0)
            throw error(409, "SASPAY_PAYOUT_PILOT_LIMIT", "Montant supérieur au plafond pilote.");
    }

    private boolean validGeneralRiskLimits() {
        return saspayPayoutGeneralMaxXof != null
                && saspayPayoutGeneralMaxXof.signum() > 0
                && saspayPayoutGeneralMaxXof.stripTrailingZeros().scale() <= 0
                && saspayPayoutGeneralMaxXof.compareTo(new BigDecimal("5000000")) <= 0
                && saspayPayoutGeneralDailyMaxXof != null
                && saspayPayoutGeneralDailyMaxXof.signum() > 0
                && saspayPayoutGeneralDailyMaxXof.stripTrailingZeros().scale() <= 0
                && saspayPayoutGeneralDailyMaxXof.compareTo(new BigDecimal("25000000")) <= 0
                && saspayPayoutGeneralDailyMaxXof.compareTo(saspayPayoutGeneralMaxXof) >= 0;
    }

    /**
     * Called under the organization lock in both quote and reservation flows.
     * Every payout attempt made that day consumes capacity, even if it was later
     * administratively closed: conservative against delayed provider execution.
     */
    void requireGeneralDailyAllowance(UUID org, BigDecimal gross, UUID currentPayoutId) {
        if (!"SASPAY".equals(provider.name()) || !saspayGeneralPayoutsEnabled) return;
        requireSaspayPilot(org, gross);
        var day = now().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        BigDecimal committed = BigDecimal.ZERO;
        for (var payout : store.payouts(org)) {
            if (payout.id().equals(currentPayoutId)
                    || !"SASPAY".equals(payout.provider())
                    || payout.requestedAt() == null
                    || !day.equals(payout.requestedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate()))
                continue;
            if (payout.amount() == null || payout.amount().signum() < 0)
                throw error(
                        409,
                        "SASPAY_PAYOUT_PRODUCTION_LEDGER_INVALID",
                        "Historique de retraits incohérent.");
            committed = committed.add(payout.amount());
        }
        if (committed.add(gross).compareTo(saspayPayoutGeneralDailyMaxXof) > 0)
            throw error(
                    409,
                    "SASPAY_PAYOUT_PRODUCTION_DAILY_LIMIT",
                    "Plafond journalier atteint pour cette boutique.");
    }

    void checkPilotUsage(UUID org) {
        checkPilotUsage(org, null, null, null);
    }

    private UUID checkPilotUsage(UUID org, UUID order, UUID destination, BigDecimal amount) {
        requireNoResolutionIncident(org);
        if (!"SASPAY".equals(provider.name())
                || saspayGeneralPayoutsEnabled
                || !saspayPayoutPilotEnabled) return null;
        var unresolved =
                store.payouts(org).stream()
                        .filter(
                                p ->
                                        "SASPAY".equals(p.provider())
                                                && (p.reserved() || p.reconciliationRequired()))
                        .toList();
        if (unresolved.isEmpty()) return null;
        var grant =
                store.independentPilot(org, order, destination, amount, provider.account(), now());
        if (grant.isPresent() && unresolved.size() == 1) {
            var original = unresolved.getFirst();
            if (original.id().equals(grant.get().uncertainPayoutId())
                    && "REVIEW_REQUIRED".equals(original.status())
                    && original.providerTransactionId() == null
                    && !Objects.equals(original.directOrderId(), order)
                    && original.amount().compareTo(new BigDecimal("1000")) == 0)
                return grant.get().id();
        }
        throw error(
                409,
                "SASPAY_PAYOUT_PILOT_ALREADY_USED",
                "Un retrait SasPay pilote non résolu existe ; une autorisation indépendante est requise.");
    }

    private void requireNoResolutionIncident(UUID org) {
        if (store.hasResolutionIncident(org))
            throw error(
                    409,
                    "PAYOUT_RESOLUTION_CONTRADICTION",
                    "Preuve tardive contradictoire : les nouveaux retraits sont suspendus.");
    }

    private MerchantPayoutFeeQuote requireQuote(MerchantPayout payout, UUID quoteId) {
        if (quoteId == null)
            throw error(
                    409,
                    "PAYOUT_FEE_QUOTE_REQUIRED",
                    "Veuillez accepter le devis de retrait et ses frais avant de confirmer.");
        return store.feeQuote(payout.organizationId(), quoteId);
    }

    public MerchantPayoutFeeQuote quoteCampaign(UUID org, UUID campaign, UUID expectedDestination) {
        return quoteCampaign(org, campaign, expectedDestination, null);
    }

    public MerchantPayoutFeeQuote quoteCampaign(
            UUID org, UUID campaign, UUID expectedDestination, BigDecimal requestedAmount) {
        access.requireActorPermission(org, OrganizationPermission.EXECUTE_PAYOUTS);
        requireLivePilotTarget(null, null);
        if (!available() || !"SASPAY".equals(provider.name()))
            throw error(
                    503,
                    "PAYOUT_FEE_QUOTE_UNAVAILABLE",
                    "Le devis de retrait SasPay est indisponible.");
        store.lockOrganization(org);
        var campaignFacts = campaigns.lock(org, campaign);
        if (!campaignFacts.available())
            throw error(409, "MERCHANT_FUNDS_NOT_AVAILABLE", "Ce groupage n'est pas clôturé.");
        BigDecimal gross =
                balances.calculate(org, campaign, true, false, true).campaigns().stream()
                        .filter(row -> campaign.equals(row.campaignId()))
                        .findFirst()
                        .orElseThrow()
                        .availableForPayout();
        return issueQuote(org, campaign, null, expectedDestination, gross, requestedAmount);
    }

    public MerchantPayoutFeeQuote quoteDirect(
            UUID org, UUID directOrderId, UUID expectedDestination) {
        return quoteDirect(org, directOrderId, expectedDestination, null);
    }

    public MerchantPayoutFeeQuote quoteDirect(
            UUID org, UUID directOrderId, UUID expectedDestination, BigDecimal requestedAmount) {
        access.requireActorPermission(org, OrganizationPermission.EXECUTE_PAYOUTS);
        requireLivePilotTarget(directOrderId, null);
        if (!available() || !directPayoutsEnabled || !"SASPAY".equals(provider.name()))
            throw error(
                    503,
                    "PAYOUT_FEE_QUOTE_UNAVAILABLE",
                    "Le devis de retrait SasPay est indisponible.");
        store.lockOrganization(org);
        direct.lock(org, directOrderId);
        BigDecimal gross =
                direct.calculate(org).stream()
                        .filter(row -> directOrderId.equals(row.directOrderId()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        error(
                                                409,
                                                "MERCHANT_FUNDS_NOT_AVAILABLE",
                                                "Cette vente n'a pas de fonds disponibles."))
                        .availableForPayout();
        gross = gross.min(balances.calculate(org, null, true, false, true).availableForPayout());
        return issueQuote(org, null, directOrderId, expectedDestination, gross, requestedAmount);
    }

    private MerchantPayoutFeeQuote issueQuote(
            UUID org,
            UUID campaign,
            UUID directOrderId,
            UUID expectedDestination,
            BigDecimal availableGross,
            BigDecimal requestedAmount) {
        if (availableGross == null || availableGross.signum() <= 0)
            throw error(409, "MERCHANT_FUNDS_NOT_AVAILABLE", "Aucun solde à retirer.");
        if ("SASPAY".equals(provider.name())
                && saspayPayoutPilotEnabled
                && !saspayGeneralPayoutsEnabled
                && requestedAmount == null)
            throw error(
                    400, "SASPAY_PAYOUT_PILOT_AMOUNT_REQUIRED", "Indiquez le montant à retirer.");
        BigDecimal gross = requestedAmount == null ? availableGross : requestedAmount;
        if (gross == null
                || gross.signum() <= 0
                || gross.stripTrailingZeros().scale() > 0
                || gross.compareTo(availableGross) > 0)
            throw error(409, "PAYOUT_AMOUNT_UNAVAILABLE", "Montant de retrait indisponible.");
        requireSaspayPilot(org, gross);
        requireGeneralDailyAllowance(org, gross, null);
        var destination =
                store.destination(org)
                        .orElseThrow(
                                () ->
                                        error(
                                                409,
                                                "PAYOUT_DESTINATION_REQUIRED",
                                                "Renseignez une destination de versement."));
        if (!destination.id().equals(expectedDestination))
            throw error(
                    409,
                    "PAYOUT_DESTINATION_CHANGED",
                    "La destination a changé. Actualisez le devis.");
        requireLivePilotTarget(directOrderId, destination.method());
        checkPilotUsage(org, directOrderId, expectedDestination, gross);
        BigDecimal fee = provider.payoutFeeQuote(gross, destination.method());
        if (fee == null || fee.signum() < 0 || fee.scale() > 2 || fee.compareTo(gross) >= 0)
            throw error(409, "PAYOUT_FEE_QUOTE_INVALID", "Frais de retrait non fiables.");
        var quote =
                new MerchantPayoutFeeQuote(
                        UUID.randomUUID(),
                        org,
                        campaign,
                        directOrderId,
                        destination.id(),
                        destination.method(),
                        destination.phoneE164(),
                        provider.name(),
                        provider.account(),
                        gross,
                        fee,
                        gross.subtract(fee),
                        now().plusSeconds(180));
        store.createFeeQuote(quote);
        return quote;
    }

    public MerchantPayout reserve(
            UUID org, UUID campaign, UUID key, UUID expectedDestination, boolean confirmed) {
        return reserve(org, campaign, key, expectedDestination, confirmed, null);
    }

    public MerchantPayout reserve(
            UUID org,
            UUID campaign,
            UUID key,
            UUID expectedDestination,
            boolean confirmed,
            UUID quoteId) {
        UUID actor = access.requireActorPermission(org, OrganizationPermission.EXECUTE_PAYOUTS);
        requireLivePilotTarget(null, null);
        if (!confirmed)
            throw error(400, "PAYOUT_CONFIRMATION_REQUIRED", "Confirmez la demande de versement.");
        store.lockOrganization(org);
        String reference = "MERCHANT_PAYOUT:" + org + ":" + key;
        var old = store.byReference(org, reference);
        if (old.isPresent()) {
            if (!Objects.equals(old.get().campaignId(), campaign)
                    || !old.get().destinationId().equals(expectedDestination)
                    || ("SASPAY".equals(old.get().provider())
                            && !store.findPayoutFeeQuote(old.get().id())
                                    .map(q -> q.id().equals(quoteId))
                                    .orElse(false)))
                throw error(
                        409,
                        "PAYOUT_IDEMPOTENCY_CONFLICT",
                        "Cette demande correspond à un autre versement.");
            return old.get();
        }
        if (!available())
            throw error(
                    503, "MERCHANT_PAYOUT_DISABLED", "Le versement automatique est indisponible.");
        var c = campaigns.lock(org, campaign);
        if (!c.available())
            throw error(
                    409,
                    "MERCHANT_FUNDS_NOT_AVAILABLE",
                    "Les fonds seront disponibles après la clôture des ventes.");
        if (store.payouts(org).stream()
                .anyMatch(p -> Objects.equals(p.campaignId(), campaign) && p.reserved()))
            throw error(
                    409,
                    "MERCHANT_PAYOUT_IN_PROGRESS",
                    "Un versement est déjà en cours pour ce groupage.");
        var d =
                store.destination(org)
                        .orElseThrow(
                                () ->
                                        error(
                                                409,
                                                "PAYOUT_DESTINATION_REQUIRED",
                                                "Renseignez une destination de versement."));
        if (!d.id().equals(expectedDestination))
            throw error(
                    409,
                    "PAYOUT_DESTINATION_CHANGED",
                    "La destination a changé. Vérifiez-la avant de confirmer.");
        var position =
                balances.calculate(org, campaign, true, false, true).campaigns().stream()
                        .filter(r -> r.campaignId().equals(campaign))
                        .findFirst()
                        .orElseThrow();
        if (position.availableForPayout().signum() <= 0)
            throw error(
                    409, "MERCHANT_FUNDS_NOT_AVAILABLE", "Aucun montant disponible au versement.");
        BigDecimal requestedGross = position.availableForPayout();
        if ("SASPAY".equals(provider.name())) {
            var accepted = requireQuoteForAmount(org, quoteId);
            requestedGross = accepted.grossAmount();
            requireSaspayPilot(org, requestedGross);
            requireGeneralDailyAllowance(org, requestedGross, null);
            checkPilotUsage(org);
            if (requestedGross.compareTo(position.availableForPayout()) > 0)
                throw error(409, "PAYOUT_AMOUNT_UNAVAILABLE", "Le solde disponible a changé.");
        }
        var p =
                new MerchantPayout(
                        UUID.randomUUID(),
                        org,
                        campaign,
                        requestedGross,
                        "XOF",
                        "CREATED",
                        d.id(),
                        d.method(),
                        d.phoneE164(),
                        d.holderName(),
                        provider.name(),
                        provider.account(),
                        reference,
                        UUID.randomUUID(),
                        null,
                        actor,
                        now(),
                        null,
                        null,
                        true,
                        "AWAITING_DISPATCH",
                        0);
        store.create(p);
        if ("SASPAY".equals(p.provider())) store.bindFeeQuote(requireQuote(p, quoteId), p);
        outgoing.register(
                new OutgoingPayoutOperations.Registration(
                        Kind.MERCHANT_PAYOUT,
                        p.id(),
                        org,
                        p.provider(),
                        p.providerAccountReference(),
                        p.businessReference(),
                        p.providerIdempotencyKey(),
                        p.amount(),
                        p.currency(),
                        p.method(),
                        p.requestedAt()));
        return p;
    }

    public MerchantPayout reserveDirect(
            UUID org, UUID directOrderId, UUID key, UUID expectedDestination, boolean confirmed) {
        return reserveDirect(org, directOrderId, key, expectedDestination, confirmed, null);
    }

    public MerchantPayout reserveDirect(
            UUID org,
            UUID directOrderId,
            UUID key,
            UUID expectedDestination,
            boolean confirmed,
            UUID quoteId) {
        UUID actor = access.requireActorPermission(org, OrganizationPermission.EXECUTE_PAYOUTS);
        requireLivePilotTarget(directOrderId, null);
        if (!confirmed)
            throw error(400, "PAYOUT_CONFIRMATION_REQUIRED", "Confirmez la demande de versement.");
        store.lockOrganization(org);
        String reference = "MERCHANT_PAYOUT:" + org + ":" + key;
        var old = store.byReference(org, reference);
        if (old.isPresent()) {
            if (!Objects.equals(old.get().directOrderId(), directOrderId)
                    || !old.get().destinationId().equals(expectedDestination)
                    || ("SASPAY".equals(old.get().provider())
                            && !store.findPayoutFeeQuote(old.get().id())
                                    .map(q -> q.id().equals(quoteId))
                                    .orElse(false)))
                throw error(
                        409,
                        "PAYOUT_IDEMPOTENCY_CONFLICT",
                        "Cette demande correspond à un autre versement.");
            return old.get();
        }
        if (!available() || !directPayoutsEnabled)
            throw error(
                    503, "MERCHANT_PAYOUT_DISABLED", "Le versement automatique est indisponible.");
        direct.lock(org, directOrderId);
        if (store.payouts(org).stream()
                .anyMatch(p -> Objects.equals(p.directOrderId(), directOrderId) && p.reserved()))
            throw error(
                    409,
                    "MERCHANT_PAYOUT_IN_PROGRESS",
                    "Un versement est déjà réservé pour cette vente.");
        var d =
                store.destination(org)
                        .orElseThrow(
                                () ->
                                        error(
                                                409,
                                                "PAYOUT_DESTINATION_REQUIRED",
                                                "Renseignez une destination de versement."));
        if (!d.id().equals(expectedDestination))
            throw error(
                    409,
                    "PAYOUT_DESTINATION_CHANGED",
                    "La destination a changé. Vérifiez-la avant de confirmer.");
        requireLivePilotTarget(directOrderId, d.method());
        // Calculate after the organization and direct-order locks, never trust a browser amount.
        var position =
                direct.calculate(org).stream()
                        .filter(row -> row.directOrderId().equals(directOrderId))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        error(
                                                409,
                                                "MERCHANT_FUNDS_NOT_AVAILABLE",
                                                "Le règlement de cette vente n'est pas encore disponible."));
        BigDecimal spendable =
                position.availableForPayout()
                        .min(balances.calculate(org, null, true, false, true).availableForPayout());
        if (spendable.signum() <= 0)
            throw error(
                    409,
                    "MERCHANT_FUNDS_NOT_AVAILABLE",
                    "Aucun montant vérifié disponible pour cette vente.");
        UUID pilotGrant = null;
        BigDecimal requestedGross = spendable;
        if ("SASPAY".equals(provider.name())) {
            var accepted = requireQuoteForAmount(org, quoteId);
            requestedGross = accepted.grossAmount();
            requireSaspayPilot(org, requestedGross);
            requireGeneralDailyAllowance(org, requestedGross, null);
            pilotGrant = checkPilotUsage(org, directOrderId, expectedDestination, requestedGross);
            if (requestedGross.compareTo(spendable) > 0)
                throw error(409, "PAYOUT_AMOUNT_UNAVAILABLE", "Le solde disponible a changé.");
        }
        var p =
                new MerchantPayout(
                        UUID.randomUUID(),
                        org,
                        null,
                        requestedGross,
                        "XOF",
                        "CREATED",
                        d.id(),
                        d.method(),
                        d.phoneE164(),
                        d.holderName(),
                        provider.name(),
                        provider.account(),
                        reference,
                        UUID.randomUUID(),
                        null,
                        actor,
                        now(),
                        null,
                        null,
                        true,
                        "AWAITING_DISPATCH",
                        0,
                        directOrderId);
        store.create(p);
        if (pilotGrant != null) store.bindIndependentPilot(pilotGrant, p.id());
        if ("SASPAY".equals(p.provider())) store.bindFeeQuote(requireQuote(p, quoteId), p);
        outgoing.register(
                new OutgoingPayoutOperations.Registration(
                        Kind.MERCHANT_PAYOUT,
                        p.id(),
                        org,
                        p.provider(),
                        p.providerAccountReference(),
                        p.businessReference(),
                        p.providerIdempotencyKey(),
                        p.amount(),
                        p.currency(),
                        p.method(),
                        p.requestedAt()));
        return p;
    }

    public MerchantPayoutDestination destination(
            UUID org,
            UUID previous,
            String method,
            String phone,
            String holder,
            boolean confirmed) {
        UUID actor =
                access.requireActorPermission(
                        org, OrganizationPermission.MANAGE_PAYOUT_DESTINATION);
        if (!confirmed)
            throw error(400, "PAYOUT_CONFIRMATION_REQUIRED", "Confirmez la nouvelle destination.");
        if (method == null || !Set.of("WAVE", "ORANGE_MONEY").contains(method))
            throw error(400, "PAYOUT_DESTINATION_INVALID", "Choisissez Wave ou Orange Money.");
        phone = phone == null ? "" : phone.replaceAll("[\\s().-]", "");
        if (phone.matches("[0-9]{9}")) phone = "+221" + phone;
        if (phone.startsWith("00221")) phone = "+" + phone.substring(2);
        if (phone.matches("221[0-9]{9}")) phone = "+" + phone;
        holder = holder == null ? "" : holder.strip();
        if (!phone.matches("\\+221[0-9]{9}")
                || holder.isBlank()
                || holder.length() > 160
                || holder.codePoints().anyMatch(Character::isISOControl))
            throw error(
                    400,
                    "PAYOUT_DESTINATION_INVALID",
                    "Vérifiez le titulaire et le numéro sénégalais.");
        store.lockOrganization(org);
        var current = store.destination(org);
        if (!Objects.equals(current.map(MerchantPayoutDestination::id).orElse(null), previous))
            throw error(
                    409,
                    "PAYOUT_DESTINATION_CHANGED",
                    "La destination a changé. Actualisez avant de la modifier.");
        var d =
                new MerchantPayoutDestination(
                        UUID.randomUUID(),
                        org,
                        method,
                        phone,
                        holder,
                        "ACTIVE",
                        now(),
                        now(),
                        actor,
                        actor,
                        0);
        store.replaceDestination(d);
        return d;
    }

    public MerchantPayout get(UUID org, UUID id) {
        return store.payout(org, id);
    }

    public boolean claim(UUID org, UUID id) {
        store.lockOrganization(org);
        var p = store.payout(org, id);
        if (!p.status().equals("CREATED")) return false;
        requireNoResolutionIncident(org);
        store.validateIndependentPilot(id, now());
        requireLivePilotTarget(p.directOrderId(), p.method());
        if (!available() || (p.directSale() && !directPayoutsEnabled))
            throw error(
                    503, "MERCHANT_PAYOUT_DISABLED", "Le versement automatique est indisponible.");
        if (p.directSale()) {
            // Recheck after reservation and before the first irreversible provider call.
            // This already includes the current payout's own reserved principal.
            direct.lock(org, p.directOrderId());
            var position =
                    direct.calculate(org).stream()
                            .filter(row -> row.directOrderId().equals(p.directOrderId()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            error(
                                                    409,
                                                    "MERCHANT_FUNDS_NOT_AVAILABLE",
                                                    "Le règlement de cette vente n'est pas disponible."));
            if (position.eligibleBalance().compareTo(position.reservedForPayout()) < 0
                    || position.reservedForPayout().compareTo(p.amount()) < 0
                    || position.deficit().signum() != 0)
                throw error(
                        409,
                        "MERCHANT_FUNDS_NOT_AVAILABLE",
                        "Le solde de la vente a changé. Le versement reste réservé pour vérification.");
        }
        if (!p.directSale() && "SASPAY".equals(p.provider())) {
            // The original quote is not a substitute for a second solvency check.
            // Check the campaign again under the organization lock, before provider POST.
            campaigns.lock(org, p.campaignId());
            var position =
                    balances.calculate(org, p.campaignId(), true, false, true).campaigns().stream()
                            .filter(row -> p.campaignId().equals(row.campaignId()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            error(
                                                    409,
                                                    "MERCHANT_FUNDS_NOT_AVAILABLE",
                                                    "Les fonds du groupage sont indisponibles."));
            if (position.eligibleBalance().compareTo(position.reservedForPayout()) < 0
                    || position.reservedForPayout().compareTo(p.amount()) < 0
                    || position.deficit().signum() != 0)
                throw error(
                        409,
                        "MERCHANT_FUNDS_NOT_AVAILABLE",
                        "Le solde du groupage a changé. Le retrait reste réservé pour vérification.");
        }
        if (p.directSale() || "SASPAY".equals(p.provider())) requireOrganizationReservation(org);
        if ("SASPAY".equals(p.provider())) {
            if (!"SASPAY".equals(provider.name()))
                throw error(
                        503,
                        "SASPAY_PAYOUT_PROVIDER_CHANGED",
                        "Le fournisseur de retrait a changé.");
            requireSaspayPilot(org, p.amount());
            requireGeneralDailyAllowance(org, p.amount(), p.id());
            var quote = store.payoutFeeQuote(p.id());
            if (quote == null)
                throw error(
                        409,
                        "PAYOUT_FEE_QUOTE_MISSING",
                        "Aucun devis accepté n'est lié au retrait.");
            if (!p.organizationId().equals(quote.organizationId())
                    || !p.destinationId().equals(quote.destinationId())
                    || !p.method().equals(quote.method())
                    || !p.phoneE164().equals(quote.phoneE164())
                    || !p.providerAccountReference().equals(quote.providerAccountReference())
                    || p.amount().compareTo(quote.grossAmount()) != 0
                    || quote.netAmount()
                                    .compareTo(quote.grossAmount().subtract(quote.providerFee()))
                            != 0
                    || quote.netAmount().signum() <= 0)
                throw error(
                        409,
                        "PAYOUT_FEE_QUOTE_MISMATCH",
                        "Le devis accepté ne correspond plus au retrait.");
        }
        store.state(id, "PENDING", null, null, null, true, "PAYOUT_INITIATION_IN_PROGRESS");
        io.micrometer.core.instrument.Metrics.counter(
                        "merchant_payout_initiated", "provider", p.provider(), "method", p.method())
                .increment();
        return true;
    }

    private void requireOrganizationReservation(UUID org) {
        var funds = balances.calculate(org, null, true, false, true);
        if (funds.eligibleBalance().compareTo(funds.reservedForPayout()) < 0)
            throw error(
                    409,
                    "MERCHANT_FUNDS_NOT_AVAILABLE",
                    "Le solde global a changé. Le retrait reste réservé pour vérification.");
    }

    /**
     * Claims the one existing pilot payout for a SINGLE manual replay of the exact original
     * provider intention. The claim is committed before any HTTP call; a crash or timeout cannot
     * make it automatically eligible for another attempt.
     */
    public MerchantPayout claimRecovery(UUID org, UUID id, boolean confirmed) {
        access.requireActorPermission(org, OrganizationPermission.EXECUTE_PAYOUTS);
        access.requireActorPermission(org, OrganizationPermission.MANAGE_PAYOUT_DESTINATION);
        if (!confirmed)
            throw error(
                    400, "PAYOUT_CONFIRMATION_REQUIRED", "Confirmez la récupération du retrait.");
        if (!saspayPayoutRecoveryEnabled
                || id == null
                || !id.toString().equals(saspayPayoutRecoveryId)
                || !saspayLivePilotReleased)
            throw error(403, "SASPAY_PAYOUT_RECOVERY_LOCKED", "Récupération pilote désactivée.");
        store.lockOrganization(org);
        var p = store.payout(org, id);
        if (!"SASPAY".equals(provider.name())
                || !"SASPAY".equals(p.provider())
                || !p.directSale()
                || !p.status().equals("REVIEW_REQUIRED")
                || !p.reconciliationRequired()
                || !"PAYOUT_REFERENCE_UNAVAILABLE".equals(p.reconciliationReason())
                || p.providerTransactionId() != null
                || p.providerIdempotencyKey() == null
                || !p.providerIdempotencyKey().toString().equals(saspayPayoutRecoveryOriginalKey)
                || p.businessReference() == null
                || !p.businessReference().startsWith("MERCHANT_PAYOUT:" + org + ":")
                || !Objects.equals(p.providerAccountReference(), provider.account()))
            throw error(409, "SASPAY_PAYOUT_RECOVERY_INELIGIBLE", "Retrait non récupérable.");
        requireLivePilotTarget(p.directOrderId(), p.method());
        requireSaspayPilot(org, p.amount());
        if (!available() || !directPayoutsEnabled)
            throw error(503, "MERCHANT_PAYOUT_DISABLED", "Retraits désactivés.");
        if (store.payouts(org).stream().filter(row -> "SASPAY".equals(row.provider())).count() != 1)
            throw error(409, "SASPAY_PAYOUT_RECOVERY_INELIGIBLE", "Plusieurs retraits SasPay.");
        var destination =
                store.destination(org)
                        .orElseThrow(
                                () ->
                                        error(
                                                409,
                                                "PAYOUT_DESTINATION_REQUIRED",
                                                "Destination absente."));
        if (!p.destinationId().equals(destination.id())
                || !p.method().equals(destination.method())
                || !p.phoneE164().equals(destination.phoneE164())
                || !p.holderName().equals(destination.holderName()))
            throw error(409, "PAYOUT_DESTINATION_CHANGED", "La destination a changé.");
        var quote = store.payoutFeeQuote(id);
        if (quote == null
                || !org.equals(quote.organizationId())
                || !p.directOrderId().equals(quote.directOrderId())
                || !p.destinationId().equals(quote.destinationId())
                || !p.method().equals(quote.method())
                || !p.phoneE164().equals(quote.phoneE164())
                || !p.providerAccountReference().equals(quote.providerAccountReference())
                || !"SASPAY".equals(quote.provider())
                || p.amount().compareTo(quote.grossAmount()) != 0
                || quote.providerFee().signum() < 0
                || quote.netAmount().signum() <= 0
                || quote.netAmount().compareTo(quote.grossAmount().subtract(quote.providerFee()))
                        != 0)
            throw error(409, "PAYOUT_FEE_QUOTE_MISMATCH", "Devis initial non fiable.");
        // The original quote was accepted during initial reservation; its expiration is
        // intentionally not renewed. initiate() rechecks today's exact fee before POST.
        direct.lock(org, p.directOrderId());
        var position =
                direct.calculate(org).stream()
                        .filter(row -> p.directOrderId().equals(row.directOrderId()))
                        .findFirst()
                        .orElseThrow(
                                () -> error(409, "MERCHANT_FUNDS_NOT_AVAILABLE", "Fonds absents."));
        if (position.eligibleBalance().compareTo(position.reservedForPayout()) < 0
                || position.reservedForPayout().compareTo(p.amount()) < 0
                || position.deficit().signum() != 0)
            throw error(409, "MERCHANT_FUNDS_NOT_AVAILABLE", "Fonds réservés non vérifiés.");
        requireOrganizationReservation(org);
        store.state(id, "PENDING", null, null, null, true, "PAYOUT_MANUAL_RECOVERY_IN_PROGRESS");
        return p;
    }

    /** No fresh key is ever minted on a failed recovery; it remains reserved. */
    public void recoveryUncertain(UUID org, UUID id) {
        store.lockOrganization(org);
        var p = store.payout(org, id);
        if (p.reserved() && p.providerTransactionId() == null)
            review(p, "PAYOUT_MANUAL_RECOVERY_UNCERTAIN");
    }

    public void initiated(UUID org, UUID id, String transaction) {
        store.lockOrganization(org);
        var p = store.payout(org, id);
        if (transaction == null || !transaction.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")) {
            review(p, "PAYOUT_INITIATION_UNCERTAIN");
            return;
        }
        if (p.nonExecuted()) {
            store.resolutionIncident(id, "PAYOUT_ACK_AFTER_NON_EXECUTION");
            outgoing.incident(Kind.MERCHANT_PAYOUT, id, "PAYOUT_ACK_AFTER_NON_EXECUTION");
            return;
        }
        String registryError = outgoing.bind(Kind.MERCHANT_PAYOUT, id, transaction);
        if (registryError != null) {
            review(p, registryError);
            return;
        }
        store.lockTransaction(p.provider(), p.providerAccountReference(), transaction);
        if (store.transactionTaken(p.provider(), p.providerAccountReference(), transaction, id)) {
            review(p, "PAYOUT_TRANSACTION_ALREADY_USED");
            return;
        }
        if (p.providerTransactionId() != null && !p.providerTransactionId().equals(transaction)) {
            review(p, "PAYOUT_TRANSACTION_MISMATCH");
            return;
        }
        if (p.succeeded()) return;
        if (p.status().equals("PENDING")
                && Set.of("PAYOUT_INITIATION_IN_PROGRESS", "PAYOUT_MANUAL_RECOVERY_IN_PROGRESS")
                        .contains(p.reconciliationReason()))
            store.state(id, "PENDING", transaction, null, null, false, null);
        else
            store.state(
                    id,
                    p.status(),
                    transaction,
                    null,
                    null,
                    p.reconciliationRequired(),
                    p.reconciliationReason());
    }

    // Proven pre-POST failures and explicit provider authorization rejections release the intent.
    // Timeouts and ambiguous POST responses keep their reservation.
    public void initiationFailure(UUID org, UUID id, PayoutProviderFailure failure) {
        store.lockOrganization(org);
        var p = store.payout(org, id);
        if ("PENDING".equals(p.status())
                && "PAYOUT_INITIATION_IN_PROGRESS".equals(p.reconciliationReason())) {
            if (failure.submission() == PayoutProviderFailure.Submission.NOT_ATTEMPTED
                    || failure.submission() == PayoutProviderFailure.Submission.REJECTED) {
                // A terminal FAILED payout needs no further reconciliation. The database
                // invariant requires reconciliation_required=false iff reason is NULL.
                // Keep the provider rejection code in the sanitized operation logs.
                store.state(id, "FAILED", null, null, null, false, null);
            } else {
                review(p, failure.getMessage());
            }
        }
    }

    private static boolean manualRecovery(MerchantPayout p) {
        return "PAYOUT_MANUAL_RECOVERY_IN_PROGRESS".equals(p.reconciliationReason())
                || "PAYOUT_MANUAL_RECOVERY_UNCERTAIN".equals(p.reconciliationReason());
    }

    public void uncertain(UUID org, UUID id) {
        store.lockOrganization(org);
        var p = store.payout(org, id);
        if (manualRecovery(p)) {
            if ("PAYOUT_MANUAL_RECOVERY_IN_PROGRESS".equals(p.reconciliationReason()))
                review(p, "PAYOUT_MANUAL_RECOVERY_UNCERTAIN");
            return;
        }
        if (p.reserved() || p.succeeded()) review(p, "PAYOUT_PROVIDER_UNCERTAIN");
    }

    public void requiresOperator(UUID org, UUID id) {
        store.lockOrganization(org);
        var p = store.payout(org, id);
        // A crash during manual recovery must never make it eligible for a second replay.
        if (manualRecovery(p)) {
            if ("PAYOUT_MANUAL_RECOVERY_IN_PROGRESS".equals(p.reconciliationReason()))
                review(p, "PAYOUT_MANUAL_RECOVERY_UNCERTAIN");
            return;
        }
        // Do not replace a known collision or correlation failure with a less precise diagnosis.
        if (p.status().equals("REVIEW_REQUIRED")
                && p.reconciliationReason() != null
                && !Set.of("PAYOUT_PROVIDER_UNCERTAIN", "PAYOUT_INITIATION_UNCERTAIN")
                        .contains(p.reconciliationReason())) return;
        review(p, "PAYOUT_REFERENCE_UNAVAILABLE");
    }

    /**
     * Only the server-side provider adapter supplies this evidence. Never exposed as an HTTP
     * mutation.
     */
    public void verified(UUID org, UUID id, MerchantPayoutProvider.Proof proof) {
        store.lockOrganization(org);
        var p = store.payout(org, id);
        String error = correlate(p, proof);
        // An apparently impossible provider receipt for a terminal failed initiation
        // with no transaction identifier is a financial incident, not a second debit.
        // This check must NOT rely on reconciliationReason: that field is NULL
        // whenever reconciliationRequired is false by a database CHECK constraint.
        if ("FAILED".equals(p.status()) && p.providerTransactionId() == null && error == null) {
            store.resolutionIncident(id, "PAYOUT_PROOF_AFTER_FAILED_INITIATION");
            outgoing.incident(Kind.MERCHANT_PAYOUT, id, "PAYOUT_PROOF_AFTER_FAILED_INITIATION");
            return;
        }
        if (p.nonExecuted()) {
            if (error == null) {
                store.resolutionIncident(id, "PAYOUT_PROOF_AFTER_NON_EXECUTION");
                outgoing.incident(Kind.MERCHANT_PAYOUT, id, "PAYOUT_PROOF_AFTER_NON_EXECUTION");
            }
            return;
        }
        if (error == null && p.succeeded() && !"SUCCEEDED".equals(proof.status()))
            error = "PAYOUT_CONTRADICTORY_EVIDENCE";
        if (error != null) {
            review(p, error);
            return;
        }
        String registryError = outgoing.bind(Kind.MERCHANT_PAYOUT, id, proof.transactionId());
        if (registryError != null) {
            review(p, registryError);
            return;
        }
        if (p.succeeded()) return;
        store.lockTransaction(p.provider(), p.providerAccountReference(), proof.transactionId());
        if (store.transactionTaken(
                p.provider(), p.providerAccountReference(), proof.transactionId(), id)) {
            review(p, "PAYOUT_TRANSACTION_ALREADY_USED");
            return;
        }
        if (proof.status().equals("SUCCEEDED")) {
            // DEDUCTED: gross is debited, net is gross minus the fee borne by the seller.
            if ("SASPAY".equals(p.provider())) {
                BigDecimal fee = proof.providerFee();
                if (fee == null || fee.signum() < 0 || fee.scale() > 2) {
                    review(p, "PAYOUT_FEE_EVIDENCE_MISSING");
                    return;
                }
                try {
                    var accepted = store.payoutFeeQuote(p.id());
                    if (accepted.providerFee().compareTo(fee) != 0
                            || accepted.grossAmount().compareTo(p.amount()) != 0)
                        throw new IllegalStateException("PAYOUT_FEE_QUOTE_MISMATCH");
                    store.recordDeductedProviderFee(p.id(), p.amount(), fee);
                } catch (IllegalStateException ex) {
                    review(p, "PAYOUT_FEE_EVIDENCE_CONFLICT");
                    return;
                }
            }
            io.micrometer.core.instrument.Metrics.counter(
                            "merchant_payout_succeeded",
                            "provider",
                            p.provider(),
                            "method",
                            p.method())
                    .increment();
            store.state(
                    id, "SUCCEEDED", proof.transactionId(), proof.occurredAt(), now(), false, null);
            if (!p.directSale())
                store.append(
                        new MerchantLedgerEntry(
                                UUID.randomUUID(),
                                org,
                                p.campaignId(),
                                null,
                                "MERCHANT_PAYOUT",
                                "DEBIT",
                                p.amount(),
                                p.currency(),
                                BigDecimal.ZERO,
                                "PAYOUT:" + p.id(),
                                "MERCHANT_PAYOUT",
                                p.id(),
                                now()));
        } else if (proof.status().equals("PENDING")) {
            // A correlated late pending receipt can recover the ID lost with the ACK.
            // Keep the uncertainty and reservation; this only enables a subsequent GET.
            if (p.reserved() && p.providerTransactionId() == null)
                store.state(
                        id,
                        p.status(),
                        proof.transactionId(),
                        p.providerOccurredAt(),
                        null,
                        p.reconciliationRequired(),
                        p.reconciliationReason());
            if (p.status().equals("PENDING")
                    && !p.reconciliationRequired()
                    && (p.providerOccurredAt() == null
                            || proof.occurredAt() != null
                                    && !proof.occurredAt().isBefore(p.providerOccurredAt())))
                store.state(
                        id,
                        "PENDING",
                        proof.transactionId(),
                        proof.occurredAt(),
                        null,
                        false,
                        null);
        } else if (Set.of("FAILED", "CANCELLED").contains(proof.status())
                && proof.occurredAt() != null
                && (p.providerOccurredAt() == null
                        || !proof.occurredAt().isBefore(p.providerOccurredAt()))) {
            store.state(
                    id,
                    proof.status(),
                    proof.transactionId(),
                    proof.occurredAt(),
                    null,
                    false,
                    null);
            io.micrometer.core.instrument.Metrics.counter(
                            "merchant_payout_failed",
                            "provider",
                            p.provider(),
                            "method",
                            p.method(),
                            "status",
                            proof.status())
                    .increment();
        } else review(p, "PAYOUT_STATE_UNCERTAIN");
    }

    private String correlate(MerchantPayout p, MerchantPayoutProvider.Proof e) {
        if (e == null || !e.authenticated()) return "PAYOUT_PROOF_UNAUTHENTICATED";
        if (!p.provider().equals(e.provider()) || !p.providerAccountReference().equals(e.account()))
            return "PAYOUT_PROVIDER_MISMATCH";
        if (!p.businessReference().equals(e.businessReference()))
            return "PAYOUT_REFERENCE_MISMATCH";
        if (e.amount() == null || p.amount().compareTo(e.amount()) != 0)
            return "PAYOUT_AMOUNT_MISMATCH";
        if (!p.currency().equals(e.currency())) return "PAYOUT_CURRENCY_MISMATCH";
        if (!p.method().equals(e.method())
                || (e.phoneE164() != null && !p.phoneE164().equals(e.phoneE164())))
            return "PAYOUT_DESTINATION_MISMATCH";
        if (e.transactionId() == null
                || !e.transactionId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")
                || (p.providerTransactionId() != null
                        && !p.providerTransactionId().equals(e.transactionId())))
            return "PAYOUT_TRANSACTION_MISMATCH";
        if (e.status() == null) return "PAYOUT_STATE_UNCERTAIN";
        return null;
    }

    private void review(MerchantPayout p, String reason) {
        if (p.nonExecuted()) return;
        if (p.succeeded()) {
            outgoing.incident(Kind.MERCHANT_PAYOUT, p.id(), reason);
            return;
        }
        store.state(p.id(), "REVIEW_REQUIRED", null, null, null, true, reason);
        io.micrometer.core.instrument.Metrics.counter(
                        "merchant_payout_review_required",
                        "provider",
                        p.provider(),
                        "method",
                        p.method())
                .increment();
    }

    public void evidence(UUID org, UUID id, OutgoingPayoutOperations.Evidence e) {
        store.lockOrganization(org);
        var p = store.payout(org, id);
        String error = e.errorCode();
        if (e.operationType() != Kind.MERCHANT_PAYOUT || !id.equals(e.operationId()))
            error = "PAYOUT_REFERENCE_MISMATCH";
        var proof =
                new MerchantPayoutProvider.Proof(
                        e.authenticated(),
                        e.provider(),
                        e.account(),
                        e.businessReference(),
                        e.transactionId(),
                        e.amount(),
                        e.currency(),
                        e.method(),
                        e.recipientPhone(),
                        e.status(),
                        e.occurredAt(),
                        e.providerFee());
        if (error == null) error = correlate(p, proof);
        if (error == null && p.succeeded() && !"SUCCEEDED".equals(e.status()))
            error = "PAYOUT_CONTRADICTORY_EVIDENCE";
        if (error != null) review(p, error);
        else {
            verified(org, id, proof);
            var result = store.payout(org, id);
            if (result.reconciliationRequired()) error = result.reconciliationReason();
        }
        outgoing.acknowledgeEvidence(e.receiptId(), error);
    }

    private Instant now() {
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private static ApiException error(int status, String code, String message) {
        return new ApiException(status, code, message);
    }
}
