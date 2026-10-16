package sn.groupia.subscription.application.service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.event.PaymentObligationSettled;
import sn.groupia.subscription.domain.model.SubscriptionPeriod;
import sn.groupia.subscription.domain.repository.*;

@Service
class SubscriptionPaymentRecorder {
    private final SubscriptionStore subscriptions;
    private final SubscriptionInvoices invoices;
    private final PaymentOperations payments;
    private final SubscriptionEntitlementService entitlements;
    private final Clock clock;

    SubscriptionPaymentRecorder(
            SubscriptionStore subscriptions,
            SubscriptionInvoices invoices,
            PaymentOperations payments,
            SubscriptionEntitlementService entitlements,
            Clock clock) {
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.payments = payments;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    @Transactional
    void record(PaymentObligationSettled e) {
        subscriptions.lock(e.organizationId()).orElseThrow();
        var i = invoices.lock(e.organizationId(), e.target().id()).orElseThrow();
        var proof = payments.settlementEvidence(e.organizationId(), e.paymentRequestId());
        if (!i.paymentRequestId().equals(e.paymentRequestId())
                || !proof.organizationId().equals(i.organizationId())
                || !proof.target().equals(e.target())
                || !proof.paymentId().equals(e.paymentId())
                || proof.purpose() != e.purpose()
                || proof.purpose() != PaymentPurpose.SUBSCRIPTION_MONTHLY
                || proof.acceptanceStatus() != AcceptanceStatus.CLOSED
                || proof.status() != PaymentStatus.SUCCEEDED
                || proof.amount().compareTo(i.amount()) != 0
                || e.amount().compareTo(i.amount()) != 0
                || !i.currency().equals(proof.currency())
                || !i.currency().equals(e.currency())
                || !i.reference().equals(proof.paymentReference())
                || proof.providerTransactionId() == null
                || !proof.confirmedAt().equals(e.occurredAt())
                || !i.periodEnd().equals(proof.expiresAt())
                || !proof.confirmedAt().isBefore(i.periodEnd()))
            throw new IllegalStateException("Subscription settlement evidence mismatch");
        var existing =
                subscriptions.history(i.organizationId()).stream()
                        .filter(p -> i.id().equals(p.subscriptionInvoiceId()))
                        .findFirst();
        if (existing.isPresent()) {
            var p = existing.get();
            if (!i.status().equals("PAID")
                    || !e.paymentId().equals(i.settledPaymentId())
                    || !e.paymentId().equals(p.paymentId())
                    || !e.paymentRequestId().equals(p.paymentRequestId())
                    || !p.periodStart().equals(i.periodStart())
                    || !p.periodEnd().equals(i.periodEnd())
                    || p.amount().compareTo(i.amount()) != 0
                    || !p.currency().equals(i.currency())
                    || !p.provider().equals(proof.provider())
                    || !Objects.equals(
                            p.providerAccountReference(), proof.providerAccountReference())
                    || !p.providerTransactionId().equals(proof.providerTransactionId())
                    || !p.providerReference().equals(proof.paymentReference())
                    || !p.paidAt().equals(proof.confirmedAt()))
                throw new IllegalStateException("Contradictory subscription settlement replay");
            return;
        }
        if (!i.status().equals("OPEN") || proof.reversal())
            throw new IllegalStateException("Subscription invoice cannot receive entitlement");
        var period =
                new SubscriptionPeriod(
                        UUID.randomUUID(),
                        i.organizationId(),
                        i.subscriptionId(),
                        i.planCode(),
                        i.amount(),
                        i.currency(),
                        i.billingInterval(),
                        i.periodStart(),
                        i.periodEnd(),
                        proof.provider(),
                        proof.providerTransactionId(),
                        proof.paymentReference(),
                        "PAID",
                        proof.confirmedAt(),
                        clock.instant().truncatedTo(ChronoUnit.MICROS),
                        i.id(),
                        i.paymentRequestId(),
                        proof.paymentId(),
                        proof.providerAccountReference());
        subscriptions.insertPaidPeriod(period);
        invoices.markPaid(i.id(), proof.paymentId(), proof.confirmedAt());
        subscriptions.updatePaidSubscription(
                i.organizationId(),
                i.planCode(),
                i.amount(),
                i.periodStart(),
                i.periodEnd(),
                proof.provider(),
                clock.instant());
        entitlements.access(i.organizationId());
    }
}
