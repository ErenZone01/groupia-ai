package sn.groupia.subscription.application.service;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.organization.api.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.shared.api.ApiException;
import sn.groupia.subscription.api.dto.*;
import sn.groupia.subscription.domain.model.*;
import sn.groupia.subscription.domain.repository.*;
import sn.groupia.subscription.domain.service.SubscriptionBillingCalendar;

@Service
public class SubscriptionInvoiceService {
    private final SubscriptionProvisioning provisioning;
    private final SubscriptionStore subscriptions;
    private final SubscriptionInvoices invoices;
    private final PaymentOperations payments;
    private final OrganizationAccess access;
    private final SubscriptionSettings settings;
    private final Clock clock;

    SubscriptionInvoiceService(
            SubscriptionProvisioning provisioning,
            SubscriptionStore subscriptions,
            SubscriptionInvoices invoices,
            PaymentOperations payments,
            OrganizationAccess access,
            SubscriptionSettings settings,
            Clock clock) {
        this.provisioning = provisioning;
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.payments = payments;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public SubscriptionInvoiceView create(UUID org, String key) {
        access.requirePermission(org, OrganizationPermission.MANAGE_SUBSCRIPTION);
        if (key == null || !key.matches("[A-Za-z0-9._:-]{16,128}"))
            throw new ApiException(
                    400, "PAYMENT_IDEMPOTENCY_KEY_REQUIRED", "Clé de création invalide.");
        if (!settings.isEnabled())
            throw new ApiException(
                    503, "SUBSCRIPTIONS_DISABLED", "Les abonnements sont indisponibles.");
        provisioning.ensure(org);
        var subscription = subscriptions.lock(org).orElseThrow();
        var existing = invoices.byCreationKey(org, key);
        if (existing.isPresent()) return view(existing.get());
        Instant now = now();
        for (var invoice : invoices.list(org)) {
            if (!invoice.status().equals("OPEN")) continue;
            var state = payments.lockRequestState(org, invoice.paymentRequestId());
            // A settlement accepted before expiry remains attributable even if its durable listener
            // runs later.
            if (state.request().settledPaymentId() != null || now.isBefore(invoice.periodEnd()))
                throw existingInvoice(invoice.id());
            invoices.expire(invoice.id(), now);
        }
        // Do not sell a second future period before the first starts.
        var prepaid =
                subscriptions.history(org).stream()
                        .filter(p -> p.periodStart().isAfter(now))
                        .findFirst();
        if (prepaid.isPresent())
            throw new ApiException(
                    409, "SUBSCRIPTION_NEXT_PERIOD_PAID", "La prochaine période est déjà réglée.");
        var period = period(subscription, now);
        var contact =
                subscriptions
                        .findBillingContact(org)
                        .filter(c -> c.phoneE164() != null && !c.phoneE164().isBlank())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                409,
                                                "SUBSCRIPTION_BILLING_CONTACT_REQUIRED",
                                                "Renseignez les coordonnées de facturation."));
        var amount = amount();
        UUID id = UUID.randomUUID();
        var request =
                payments.createRequest(
                        CreatePaymentRequest.forSubscription(org, id, amount, period.end()));
        var invoice =
                new SubscriptionInvoice(
                        id,
                        org,
                        subscription.id(),
                        SubscriptionBillingCalendar.key(subscription.id(), period.start()),
                        "SUB-" + id,
                        "STANDARD_MONTHLY",
                        period.start(),
                        period.end(),
                        subscription.billingTimezone(),
                        amount,
                        "XOF",
                        "MONTHLY",
                        contact.name(),
                        contact.phoneE164(),
                        contact.email(),
                        contact.country(),
                        contact.locale(),
                        request.id(),
                        "OPEN",
                        null,
                        null,
                        key,
                        now,
                        null,
                        0);
        invoices.insert(invoice);
        return view(invoice);
    }

    private static ApiException existingInvoice(UUID id) {
        // No aliasing of creation keys: with a single immutable key column a second key cannot be
        // durably bound.
        return new ApiException(
                409,
                "SUBSCRIPTION_INVOICE_EXISTS",
                "Une échéance existe déjà. Reprenez son paiement.",
                Map.of("invoiceId", id.toString()));
    }

    @Transactional(readOnly = true)
    public List<SubscriptionInvoiceView> list(UUID org) {
        access.requirePermission(org, OrganizationPermission.VIEW_SUBSCRIPTION);
        return invoices.list(org).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public SubscriptionInvoiceView get(UUID org, UUID id) {
        access.requirePermission(org, OrganizationPermission.VIEW_SUBSCRIPTION);
        return view(require(org, id));
    }

    @Transactional
    public SubscriptionInvoicePreview preview(UUID org) {
        access.requirePermission(org, OrganizationPermission.VIEW_SUBSCRIPTION);
        var subscription = provisioning.ensure(org);
        var p = period(subscription, now());
        BigDecimal price;
        try {
            price = amount();
        } catch (ApiException ex) {
            price = null;
        }
        return new SubscriptionInvoicePreview(
                p.start(), p.end(), subscription.billingTimezone(), price, "XOF");
    }

    private SubscriptionBillingCalendar.Period period(OrganizationSubscription s, Instant now) {
        Instant coverage = s.trialEndsAt().isAfter(now) ? s.trialEndsAt() : null;
        for (var p : subscriptions.history(s.organizationId())) {
            if (!now.isBefore(p.periodStart())
                    && now.isBefore(p.periodEnd())
                    && (coverage == null || p.periodEnd().isAfter(coverage)))
                coverage = p.periodEnd();
        }
        return SubscriptionBillingCalendar.next(now, coverage, s.billingTimezone());
    }

    @Transactional(readOnly = true)
    public Checkout checkout(UUID org, UUID id) {
        return checkout(org, id, false);
    }

    @Transactional(readOnly = true)
    public Checkout checkout(UUID org, UUID id, boolean currentPayer) {
        access.requirePermission(org, OrganizationPermission.MANAGE_SUBSCRIPTION);
        if (!settings.isEnabled())
            throw new ApiException(
                    503, "SUBSCRIPTIONS_DISABLED", "Les abonnements sont indisponibles.");
        var invoice = require(org, id);
        if (!invoice.status().equals("OPEN") || !now().isBefore(invoice.periodEnd()))
            throw new ApiException(
                    409,
                    "SUBSCRIPTION_INVOICE_NOT_PAYABLE",
                    "Cette échéance ne peut plus être payée.");
        // The issued invoice retains its billing snapshot. A payer may update her server-owned
        // contact before a new SasPay attempt; no financial invoice value is rewritten.
        if (currentPayer) {
            var contact =
                    subscriptions
                            .findBillingContact(org)
                            .orElseThrow(
                                    () ->
                                            new ApiException(
                                                    409,
                                                    "SUBSCRIPTION_BILLING_CONTACT_REQUIRED",
                                                    "Renseignez les coordonnées du payeur."));
            return new Checkout(
                    invoice.paymentRequestId(),
                    new PaymentCheckoutContext(
                            invoice.reference(),
                            contact.name(),
                            contact.phoneE164(),
                            contact.email()));
        }
        return new Checkout(
                invoice.paymentRequestId(),
                new PaymentCheckoutContext(
                        invoice.reference(),
                        invoice.billingName(),
                        invoice.billingPhoneE164(),
                        invoice.billingEmail()));
    }

    public record Checkout(UUID paymentRequestId, PaymentCheckoutContext context) {}

    private SubscriptionInvoice require(UUID org, UUID id) {
        return invoices.find(org, id)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        404,
                                        "SUBSCRIPTION_INVOICE_NOT_FOUND",
                                        "Échéance introuvable."));
    }

    private SubscriptionInvoiceView view(SubscriptionInvoice i) {
        var state = payments.requestState(i.organizationId(), i.paymentRequestId());
        boolean canPay =
                access.hasPermission(i.organizationId(), OrganizationPermission.MANAGE_SUBSCRIPTION)
                        && settings.isEnabled()
                        && i.status().equals("OPEN")
                        && now().isBefore(i.periodEnd())
                        && state.request().settledPaymentId() == null
                        && !state.attemptInProgress()
                        && !state.reconciliationRequired()
                        && state.operationalStatus() == PaymentOperationalStatus.PENDING;
        return new SubscriptionInvoiceView(
                i.id(),
                i.organizationId(),
                i.reference(),
                i.billingPeriodKey(),
                i.periodStart(),
                i.periodEnd(),
                i.billingTimezone(),
                i.amount(),
                i.currency(),
                i.status().equals("OPEN")
                                && !now().isBefore(i.periodEnd())
                                && state.request().settledPaymentId() == null
                        ? "EXPIRED"
                        : i.status(),
                i.paymentRequestId(),
                state.request().settledPaymentId(),
                i.paidAt(),
                state.operationalStatus(),
                state.attemptInProgress(),
                state.reconciliationRequired(),
                canPay,
                access.hasPermission(i.organizationId(), OrganizationPermission.MANAGE_SUBSCRIPTION)
                        ? payments.latestAttempt(i.organizationId(), i.paymentRequestId())
                        : null);
    }

    private BigDecimal amount() {
        try {
            var amount = new BigDecimal(settings.getMonthlyXof());
            if (amount.signum() <= 0
                    || amount.stripTrailingZeros().scale() > 0
                    || amount.compareTo(new BigDecimal("1000000000000000000")) >= 0)
                throw new NumberFormatException();
            return amount.setScale(0);
        } catch (RuntimeException ex) {
            throw new ApiException(
                    503, "SUBSCRIPTION_PLAN_NOT_CONFIGURED", "Tarif d'abonnement indisponible.");
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
