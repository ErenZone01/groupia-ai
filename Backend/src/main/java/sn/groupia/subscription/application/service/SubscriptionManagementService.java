package sn.groupia.subscription.application.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.organization.api.OrganizationAccess;
import sn.groupia.organization.api.OrganizationPermission;
import sn.groupia.organization.api.OrganizationSubscriptionContext;
import sn.groupia.shared.api.ApiException;
import sn.groupia.subscription.api.SubscriptionAccessView;
import sn.groupia.subscription.api.dto.SubscriptionBillingContactView;
import sn.groupia.subscription.api.dto.SubscriptionDetails;
import sn.groupia.subscription.api.dto.SubscriptionPeriodView;
import sn.groupia.subscription.application.service.SubscriptionBillingProvider.ProviderSetup;
import sn.groupia.subscription.application.service.SubscriptionBillingProvider.ProviderSetupRequest;
import sn.groupia.subscription.domain.model.SubscriptionBillingContact;
import sn.groupia.subscription.domain.repository.SubscriptionStore;

@Service
public class SubscriptionManagementService {
    private static final Pattern PHONE = Pattern.compile("^\\+221[0-9]{9}$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final SubscriptionEntitlementService entitlements;
    private final SubscriptionProvisioning provisioning;
    private final SubscriptionSetupClaim setupClaim;
    private final SubscriptionStore subscriptions;
    private final OrganizationSubscriptionContext organizations;
    private final OrganizationAccess access;
    private final SubscriptionBillingProvider provider;
    private final SubscriptionSettings settings;
    private final Clock clock;

    SubscriptionManagementService(SubscriptionEntitlementService entitlements, SubscriptionProvisioning provisioning,
            SubscriptionSetupClaim setupClaim, SubscriptionStore subscriptions,
            OrganizationSubscriptionContext organizations, OrganizationAccess access,
            SubscriptionBillingProvider provider, SubscriptionSettings settings, Clock clock) {
        this.entitlements = entitlements;
        this.provisioning = provisioning;
        this.setupClaim = setupClaim;
        this.subscriptions = subscriptions;
        this.organizations = organizations;
        this.access = access;
        this.provider = provider;
        this.settings = settings;
        this.clock = clock;
    }

    public SubscriptionDetails details(UUID organizationId) {
        var subscription = provisioning.ensure(organizationId);
        var view = entitlements.access(organizationId);
        var contact = subscriptions.findBillingContact(organizationId).orElseGet(() ->
                new SubscriptionBillingContact(null, organizationId,
                        organizations.subscriptionFacts(organizationId).name(), null, null, "SN", "fr-FR", null, null, 0));
        return new SubscriptionDetails(view,
                access.hasPermission(organizationId, OrganizationPermission.MANAGE_SUBSCRIPTION),
                billingProviderAvailable(), subscription.providerSetupPending(), configuredPlanAmount() != null
                        && !settings.getBictorysPlanId().isBlank(),
                new SubscriptionBillingContactView(contact.name(), contact.phoneE164(), contact.email(),
                        contact.country(), contact.locale(), contact.updatedAt(), contact.updatedBy(), contact.version()));
    }

    public List<SubscriptionPeriodView> history(UUID organizationId) {
        provisioning.ensure(organizationId);
        return subscriptions.history(organizationId).stream()
                .map(period -> new SubscriptionPeriodView(period.planCode(), period.amount(), period.currency(),
                        period.billingInterval(), period.periodStart(), period.periodEnd(), period.status(), period.paidAt()))
                .toList();
    }

    public SubscriptionBillingContactView updateBillingContact(UUID organizationId, String name, String phoneE164,
            String email) {
        UUID actor = access.requireActorPermission(organizationId, OrganizationPermission.MANAGE_SUBSCRIPTION);
        var cleanName = name == null ? "" : name.trim();
        var cleanPhone = phoneE164 == null ? "" : phoneE164.trim();
        var cleanEmail = email == null || email.isBlank() ? null : email.trim();
        if (cleanName.isEmpty() || cleanName.length() > 160 || !PHONE.matcher(cleanPhone).matches()
                || (cleanEmail != null && (cleanEmail.length() > 254 || !EMAIL.matcher(cleanEmail).matches()))) {
            throw new ApiException(400, "INVALID_SUBSCRIPTION_BILLING_CONTACT", "Coordonnées de facturation invalides.");
        }
        subscriptions.saveBillingContact(organizationId, cleanName, cleanPhone, cleanEmail, actor, clock.instant());
        return subscriptions.findBillingContact(organizationId).map(SubscriptionManagementService::contactView)
                .orElseThrow(() -> new IllegalStateException("Subscription billing contact did not persist."));
    }

    public SubscriptionDetails start(UUID organizationId) {
        var subscription = provisioning.ensure(organizationId);
        if (!settings.isEnabled()) {
            throw new ApiException(503, "SUBSCRIPTIONS_DISABLED", "La gestion des abonnements est temporairement indisponible.");
        }
        if (subscription.providerSubscriptionId() != null || subscription.providerSetupPending()) {
            return details(organizationId);
        }
        if (!billingProviderAvailable()) {
            throw new ApiException(503, "SUBSCRIPTION_BILLING_UNAVAILABLE",
                    "Le paiement de l'abonnement sera bientôt disponible.");
        }
        BigDecimal amount = configuredPlanAmount();
        String planId = settings.getBictorysPlanId().trim();
        if (amount == null || planId.isEmpty()) {
            throw new ApiException(503, "SUBSCRIPTION_PLAN_NOT_CONFIGURED",
                    "Le plan de paiement de l'abonnement n'est pas encore configuré.");
        }
        var facts = organizations.subscriptionFacts(organizationId);
        var contact = subscriptions.findBillingContact(organizationId).orElse(null);
        String name = contact == null ? facts.name() : contact.name();
        String phone = contact == null ? null : contact.phoneE164();
        String email = contact == null ? null : contact.email();
        if (phone == null || phone.isBlank()) {
            throw new ApiException(409, "SUBSCRIPTION_BILLING_CONTACT_REQUIRED",
                    "Ajoutez le téléphone du propriétaire dans les coordonnées de facturation.");
        }

        UUID key = UUID.randomUUID();
        var now = clock.instant();
        if (!setupClaim.claim(organizationId, key, now)) return details(organizationId);
        var view = entitlements.access(organizationId);
        var firstPeriodStart = now.isBefore(view.trialEndsAt()) ? view.trialEndsAt() : now;
        var request = new ProviderSetupRequest(organizationId, key, "GROUPIA-SUBSCRIPTION-" + key,
                name, phone, email, "SN", "fr-FR", planId, amount, "XOF", "MONTHLY", firstPeriodStart);
        ProviderSetup setup = provider.createCustomerAndSubscription(request);
        if (setup == null || blank(setup.provider()) || blank(setup.customerId()) || blank(setup.subscriptionId())) {
            throw new ApiException(502, "SUBSCRIPTION_PROVIDER_RESPONSE_INCOMPLETE",
                    "La configuration de facturation doit être vérifiée avant toute nouvelle tentative.");
        }
        setupClaim.save(organizationId, key, setup.provider(), setup.customerId(), setup.subscriptionId(), clock.instant());
        return details(organizationId);
    }

    public SubscriptionDetails cancel(UUID organizationId) {
        var subscription = provisioning.ensure(organizationId);
        if (subscription.cancelAtPeriodEnd()) return details(organizationId);
        if (subscription.providerSetupPending()) {
            throw new ApiException(409, "SUBSCRIPTION_PROVIDER_SETUP_PENDING",
                    "La configuration de facturation doit être réconciliée avant l'annulation.");
        }
        if (subscription.providerSubscriptionId() != null) {
            if (!billingProviderAvailable()) {
                throw new ApiException(503, "SUBSCRIPTION_BILLING_UNAVAILABLE",
                        "L'annulation côté prestataire n'est pas disponible actuellement.");
            }
            provider.cancelSubscription(subscription.providerCustomerId(), subscription.providerSubscriptionId());
        }
        subscriptions.scheduleCancellation(organizationId, clock.instant());
        return details(organizationId);
    }

    private boolean billingProviderAvailable() {
        return settings.isEnabled() && settings.isBictorysEnabled() && provider.available();
    }

    private BigDecimal configuredPlanAmount() {
        try {
            String amount = settings.getMonthlyXof();
            if (amount == null || amount.isBlank()) return null;
            BigDecimal parsed = new BigDecimal(amount);
            return parsed.signum() > 0 && parsed.stripTrailingZeros().scale() <= 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static SubscriptionBillingContactView contactView(SubscriptionBillingContact contact) {
        return new SubscriptionBillingContactView(contact.name(), contact.phoneE164(), contact.email(),
                contact.country(), contact.locale(), contact.updatedAt(), contact.updatedBy(), contact.version());
    }
}
