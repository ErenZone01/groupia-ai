package sn.groupia.subscription.application.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import sn.groupia.organization.api.OrganizationAccess;
import sn.groupia.organization.api.OrganizationPermission;
import sn.groupia.organization.api.OrganizationSubscriptionContext;
import sn.groupia.shared.api.ApiException;
import sn.groupia.subscription.api.dto.SubscriptionBillingContactView;
import sn.groupia.subscription.api.dto.SubscriptionDetails;
import sn.groupia.subscription.api.dto.SubscriptionPeriodView;
import sn.groupia.subscription.domain.model.SubscriptionBillingContact;
import sn.groupia.subscription.domain.repository.SubscriptionStore;

@Service
public class SubscriptionManagementService {
    private static final Pattern PHONE = Pattern.compile("^\\+221[0-9]{9}$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final SubscriptionEntitlementService entitlements;
    private final SubscriptionProvisioning provisioning;

    private final SubscriptionStore subscriptions;
    private final OrganizationSubscriptionContext organizations;
    private final OrganizationAccess access;
    private final sn.groupia.payment.api.PaymentOperations payments;
    private final SubscriptionSettings settings;
    private final Clock clock;

    SubscriptionManagementService(
            SubscriptionEntitlementService entitlements,
            SubscriptionProvisioning provisioning,
            SubscriptionStore subscriptions,
            OrganizationSubscriptionContext organizations,
            OrganizationAccess access,
            sn.groupia.payment.api.PaymentOperations payments,
            SubscriptionSettings settings,
            Clock clock) {
        this.entitlements = entitlements;
        this.provisioning = provisioning;

        this.subscriptions = subscriptions;
        this.organizations = organizations;
        this.access = access;
        this.payments = payments;
        this.settings = settings;
        this.clock = clock;
    }

    public SubscriptionDetails details(UUID organizationId) {
        var subscription = provisioning.ensure(organizationId);
        var view = entitlements.access(organizationId);
        var contact =
                subscriptions
                        .findBillingContact(organizationId)
                        .orElseGet(
                                () ->
                                        new SubscriptionBillingContact(
                                                null,
                                                organizationId,
                                                organizations
                                                        .subscriptionFacts(organizationId)
                                                        .name(),
                                                null,
                                                null,
                                                "SN",
                                                "fr-FR",
                                                null,
                                                null,
                                                0));
        return new SubscriptionDetails(
                view,
                access.hasPermission(organizationId, OrganizationPermission.MANAGE_SUBSCRIPTION),
                billingProviderAvailable(),
                false,
                configuredPlanAmount() != null,
                new SubscriptionBillingContactView(
                        contact.name(),
                        contact.phoneE164(),
                        contact.email(),
                        contact.country(),
                        contact.locale(),
                        contact.updatedAt(),
                        contact.updatedBy(),
                        contact.version()),
                availableProviders(),
                renewal(organizationId, view));
    }

    public List<SubscriptionPeriodView> history(UUID organizationId) {
        provisioning.ensure(organizationId);
        return subscriptions.history(organizationId).stream()
                .map(
                        period ->
                                new SubscriptionPeriodView(
                                        period.planCode(),
                                        period.amount(),
                                        period.currency(),
                                        period.billingInterval(),
                                        period.periodStart(),
                                        period.periodEnd(),
                                        period.status(),
                                        period.paidAt()))
                .toList();
    }

    public SubscriptionBillingContactView updateBillingContact(
            UUID organizationId, String name, String phoneE164, String email) {
        UUID actor =
                access.requireActorPermission(
                        organizationId, OrganizationPermission.MANAGE_SUBSCRIPTION);
        var cleanName = name == null ? "" : name.trim();
        var cleanPhone = phoneE164 == null ? "" : phoneE164.trim();
        var cleanEmail = email == null || email.isBlank() ? null : email.trim();
        if (cleanName.isEmpty()
                || cleanName.length() > 160
                || !PHONE.matcher(cleanPhone).matches()
                || (cleanEmail != null
                        && (cleanEmail.length() > 254 || !EMAIL.matcher(cleanEmail).matches()))) {
            throw new ApiException(
                    400,
                    "INVALID_SUBSCRIPTION_BILLING_CONTACT",
                    "Coordonnées de facturation invalides.");
        }
        subscriptions.saveBillingContact(
                organizationId, cleanName, cleanPhone, cleanEmail, actor, clock.instant());
        return subscriptions
                .findBillingContact(organizationId)
                .map(SubscriptionManagementService::contactView)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Subscription billing contact did not persist."));
    }

    public SubscriptionDetails start(UUID organizationId) {
        throw retired();
    }

    public SubscriptionDetails cancel(UUID organizationId) {
        throw retired();
    }

    private static ApiException retired() {
        return new ApiException(
                410,
                "SUBSCRIPTION_PROVIDER_BILLING_RETIRED",
                "Utilisez le paiement d'une échéance d'abonnement.");
    }

    private boolean billingProviderAvailable() {
        return !availableProviders().isEmpty();
    }

    private List<String> availableProviders() {
        if (!settings.isEnabled()) return List.of();
        return List.of("BICTORYS", "SASPAY").stream().filter(payments::providerAvailable).toList();
    }

    private sn.groupia.subscription.api.dto.SubscriptionRenewalView renewal(
            UUID org, sn.groupia.subscription.api.SubscriptionAccessView view) {
        var due = view.trialEndsAt();
        for (var period :
                subscriptions.history(org).stream()
                        .sorted(
                                java.util.Comparator.comparing(
                                        sn.groupia.subscription.domain.model.SubscriptionPeriod
                                                ::periodStart))
                        .toList()) {
            if (!period.periodStart().isAfter(due) && period.periodEnd().isAfter(due))
                due = period.periodEnd();
            else if (!period.periodStart().isAfter(clock.instant())
                    && period.periodEnd().isAfter(due)) due = period.periodEnd();
        }
        var now = clock.instant();
        var reminder =
                view.status() == sn.groupia.subscription.api.SubscriptionStatus.SUSPENDED
                        ? "SUSPENDED"
                        : !now.isBefore(due)
                                ? "OVERDUE"
                                : !now.plus(java.time.Duration.ofDays(7)).isBefore(due)
                                        ? "DUE_SOON"
                                        : "UP_TO_DATE";
        return new sn.groupia.subscription.api.dto.SubscriptionRenewalView(due, reminder, true);
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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static SubscriptionBillingContactView contactView(SubscriptionBillingContact contact) {
        return new SubscriptionBillingContactView(
                contact.name(),
                contact.phoneE164(),
                contact.email(),
                contact.country(),
                contact.locale(),
                contact.updatedAt(),
                contact.updatedBy(),
                contact.version());
    }
}
