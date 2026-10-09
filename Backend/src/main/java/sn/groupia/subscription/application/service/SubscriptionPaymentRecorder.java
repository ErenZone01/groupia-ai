package sn.groupia.subscription.application.service;

import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.organization.api.OrganizationSubscriptionContext;
import sn.groupia.subscription.domain.model.SubscriptionPeriod;
import sn.groupia.subscription.domain.repository.SubscriptionStore;

@Service
class SubscriptionPaymentRecorder {
    private static final int STANDARD_MONTHS = 1;

    private final SubscriptionProvisioning provisioning;
    private final SubscriptionStore subscriptions;
    private final SubscriptionSettings settings;
    private final OrganizationSubscriptionContext organizations;
    private final Clock clock;

    SubscriptionPaymentRecorder(SubscriptionProvisioning provisioning, SubscriptionStore subscriptions,
            SubscriptionSettings settings, OrganizationSubscriptionContext organizations, Clock clock) {
        this.provisioning = provisioning;
        this.subscriptions = subscriptions;
        this.settings = settings;
        this.organizations = organizations;
        this.clock = clock;
    }

    @Transactional
    void recordVerifiedPayment(UUID organizationId, VerifiedSubscriptionPayment evidence) {
        var subscription = provisioning.ensure(organizationId);
        var locked = subscriptions.lock(organizationId)
                .orElseThrow(() -> new IllegalStateException("Subscription disappeared while recording payment."));
        var amount = configuredAmount();
        if (!"XOF".equals(evidence.currency()) || amount.compareTo(evidence.amount()) != 0
                || evidence.providerTransactionId() == null || evidence.providerTransactionId().isBlank()
                || evidence.providerReference() == null || evidence.providerReference().isBlank()
                || evidence.provider() == null || evidence.provider().isBlank() || evidence.paidAt() == null) {
            throw new IllegalArgumentException("Verified subscription payment does not match the configured plan.");
        }
        var facts = subscriptions.latestPaidPeriod(organizationId);
        var start = evidence.paidAt();
        if (facts.isPresent() && facts.get().periodEnd().isAfter(start)) {
            start = facts.get().periodEnd();
        } else if (facts.isEmpty() && locked.trialEndsAt().isAfter(start)) {
            start = locked.trialEndsAt();
        }
        var timezone = organizations.subscriptionFacts(organizationId).timezone();
        var end = start.atZone(ZoneId.of(timezone))
                .plusMonths(STANDARD_MONTHS).toInstant();
        var period = new SubscriptionPeriod(UUID.randomUUID(), organizationId, subscription.id(),
                "STANDARD_MONTHLY", amount, "XOF", "MONTHLY", start, end, evidence.provider(),
                evidence.providerTransactionId(), evidence.providerReference(), "PAID", evidence.paidAt(),
                clock.instant());
        if (!subscriptions.insertPaidPeriod(period)) return;
        subscriptions.updatePaidSubscription(organizationId, period.planCode(), period.amount(),
                period.periodStart(), period.periodEnd(), period.provider(), clock.instant());
    }

    private java.math.BigDecimal configuredAmount() {
        try {
            var value = new java.math.BigDecimal(settings.getMonthlyXof());
            if (value.signum() <= 0 || value.stripTrailingZeros().scale() > 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException | NullPointerException ex) {
            throw new IllegalStateException("The subscription monthly price is not configured as a positive XOF amount.", ex);
        }
    }
}
