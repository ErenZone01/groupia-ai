package sn.groupia.payment.application.service;

import java.time.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.api.event.PaymentRequestSettled;
import sn.groupia.payment.domain.model.*;
import sn.groupia.payment.domain.repository.FinancialStore;
import sn.groupia.payment.domain.service.*;
import sn.groupia.shared.api.ApiException;

@Service
@Transactional
public class PaymentTransactions {
    private final FinancialStore store;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ProviderRegistry providers;
    private final sn.groupia.payment.domain.repository.RefundStore refunds;
    private final sn.groupia.payment.domain.repository.PaymentReviewStore reviews;

    PaymentTransactions(
            FinancialStore store,
            ApplicationEventPublisher events,
            Clock clock,
            ProviderRegistry providers,
            sn.groupia.payment.domain.repository.RefundStore refunds,
            sn.groupia.payment.domain.repository.PaymentReviewStore reviews) {
        this.store = store;
        this.events = events;
        this.clock = clock;
        this.providers = providers;
        this.refunds = refunds;
        this.reviews = reviews;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentRequestView create(CreatePaymentRequest c) {
        // Validate before reusing an existing business reference.
        var candidate = PaymentRequest.create(c, clock.instant());
        store.coordinate("request:" + c.businessReference());
        var previous = store.byBusinessReference(c.businessReference());
        if (previous.isPresent()) {
            if (!previous.get().matches(c)) {
                throw new ApiException(
                        409,
                        "PAYMENT_REQUEST_CONFLICT",
                        "Référence déjà utilisée pour une autre obligation.");
            }
            return previous.get().view();
        }
        store.add(candidate);
        store.flush();
        return candidate.view();
    }

    @Transactional(readOnly = true)
    public PaymentRequestView initial(UUID org, UUID order) {
        return initialEntity(org, order).view();
    }

    public boolean cancel(UUID org, UUID order) {
        return store.lockRequest(initialEntity(org, order).getId()).cancel(clock.instant());
    }

    public record Claim(
            boolean owned, ProviderInstruction instruction, PaymentAttemptView receipt) {}

    public Claim claim(
            UUID org,
            UUID requestId,
            String provider,
            String account,
            String key,
            PaymentCheckoutContext context) {
        if (key == null || !key.strip().matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new ApiException(
                    400,
                    "PAYMENT_IDEMPOTENCY_KEY_REQUIRED",
                    "Clé d'initiation invalide ou absente.");
        }
        key = key.strip();
        var request = scopedRequest(org, requestId);
        List<PaymentObligationView> shippingStates = List.of();
        if (request.getPurpose() == PaymentPurpose.SHIPPING_BALANCE) {
            shippingStates = lockShippingRequests(org, request.getOrderId());
        }
        request = store.lockRequest(request.getId());
        var old = store.attempt(request.getId(), key);
        if (old.isPresent()) {
            var payment = old.get();
            if (!payment.getProvider().equals(provider)
                    || !Objects.equals(payment.getProviderAccountReference(), account)) {
                throw new ApiException(
                        409,
                        "PAYMENT_INITIATION_CONFLICT",
                        "Cette clé désigne une autre initiation.");
            }
            return new Claim(false, payment.instruction(request), payment.view());
        }
        UUID target = request.getId();
        if (shippingStates.stream()
                .anyMatch(s -> !s.request().id().equals(target) && s.paymentInProgress())) {
            throw new ApiException(
                    409,
                    "SHIPPING_BALANCE_PAYMENT_IN_PROGRESS",
                    "Un paiement du transport nécessite une réconciliation.");
        }
        request.requireOpen(clock.instant());
        if (store.attempts(request.getId()).stream().anyMatch(Payment::preventsNewAttempt)) {
            throw new ApiException(
                    409,
                    "PAYMENT_INITIATION_CONFLICT",
                    "Une tentative est déjà en cours ou doit être réconciliée.");
        }
        var payment = Payment.create(request, provider, account, key, clock.instant());
        payment.checkoutContext(context);
        store.add(payment);
        store.flush();
        return new Claim(true, payment.instruction(request).withCheckout(context), payment.view());
    }

    public ProviderInstruction reconciliationInstruction(UUID paymentId) {
        var payment =
                store.payment(paymentId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                404,
                                                "PAYMENT_NOT_FOUND",
                                                "Tentative introuvable."));
        return payment.instruction(store.lockRequest(payment.getPaymentRequestId()));
    }

    public PaymentAttemptView uncertain(UUID paymentId) {
        var p0 = store.payment(paymentId).orElseThrow();
        store.lockRequest(p0.getPaymentRequestId());
        var p = store.lockPayment(paymentId);
        p.checked(clock.instant());
        if (p.getStatus() != PaymentStatus.SUCCEEDED) {
            p.review("PROVIDER_STATE_UNCERTAIN");
        }
        return p.view();
    }

    public PaymentAttemptView result(UUID paymentId, ProviderObservation observation) {
        var p0 = store.payment(paymentId).orElseThrow();
        var request = store.lockRequest(p0.getPaymentRequestId());
        var payment = store.lockPayment(paymentId);
        apply(
                request,
                payment,
                observation == null ? null : observation.correlate(payment.instruction(request)));
        store.flush();
        return payment.view();
    }

    public PaymentAttemptView initiationResult(UUID paymentId, ProviderObservation observation) {
        var initial = store.payment(paymentId).orElseThrow();
        var request = store.lockRequest(initial.getPaymentRequestId());
        var payment = store.lockPayment(paymentId);
        if (observation != null
                && observation.metadata() != null
                && observation.metadata().chargeId() != null
                && observation.providerTransactionId() == null
                && observation.status() == PaymentStatus.PENDING) {
            String invalid = validate(request, payment, observation, true);
            if (invalid != null) {
                payment.review(invalid);
            } else if (payment.getStatus() != PaymentStatus.SUCCEEDED
                    && !"PROVIDER_REVERSAL".equals(payment.getReconciliationReason())) {
                // A webhook may have committed before this acknowledgement returned.
                if (payment.getStatus() == PaymentStatus.CREATED
                        && payment.getProviderTransactionId() == null) {
                    payment.apply(observation, clock.instant());
                }
                payment.checkoutDetails(observation.metadata());
                if (observation.metadata().reviewReason() != null) {
                    payment.review(observation.metadata().reviewReason().name());
                }
            }
        } else {
            String invalid = apply(request, payment, observation);
            if (invalid == null && observation != null) {
                payment.checkoutDetails(observation.metadata());
            }
        }
        store.flush();
        return payment.view();
    }

    public PaymentAttemptView verifiedInitiationResult(
            UUID paymentId, ProviderObservation observation, ProviderObservation proof) {
        var p0 = store.payment(paymentId).orElseThrow();
        var r = store.lockRequest(p0.getPaymentRequestId());
        var p = store.lockPayment(paymentId);
        String invalid = validate(r, p, observation);
        if (invalid != null) {
            p.review(invalid);
        } else if (proof == null
                || proof.status() != PaymentStatus.SUCCEEDED
                || !Objects.equals(
                        observation.providerTransactionId(), proof.providerTransactionId())
                || validate(r, p, proof) != null) {
            if (p.getStatus() != PaymentStatus.SUCCEEDED) {
                p.review("PROVIDER_STATE_UNCERTAIN");
            }
        } else {
            apply(r, p, observation);
        }
        p.checked(clock.instant());
        store.flush();
        return p.view();
    }

    private String validate(PaymentRequest r, Payment p, ProviderObservation o) {
        return validate(r, p, o, false);
    }

    private String validate(
            PaymentRequest r, Payment p, ProviderObservation o, boolean chargeAcknowledgement) {
        if (o == null || o.status() == null) {
            return "PROVIDER_STATE_UNCERTAIN";
        }
        if (!p.getMerchantReference().equals(o.merchantReference())) {
            return "UNKNOWN_REFERENCE";
        }
        if (!p.getProvider().equals(o.provider())
                || !Objects.equals(p.getProviderAccountReference(), o.providerAccountReference())) {
            return "PROVIDER_MISMATCH";
        }
        if (!r.getId().equals(o.paymentRequestId())
                || !r.getOrderId().equals(o.orderId())
                || r.getPurpose() != o.purpose()) {
            return "TARGET_MISMATCH";
        }
        if (o.metadata() != null
                && p.getPaymentReference() != null
                && !p.getPaymentReference().equals(o.metadata().paymentReference())) {
            return "PAYMENT_REFERENCE_MISMATCH";
        }
        if (o.amount() == null
                || p.getExpectedAmount().compareTo(o.amount()) != 0
                || r.getExpectedAmount().compareTo(o.amount()) != 0) {
            return "AMOUNT_MISMATCH";
        }
        if (!p.getCurrency().equals(o.currency()) || !r.getCurrency().equals(o.currency())) {
            return "CURRENCY_MISMATCH";
        }
        if (chargeAcknowledgement) {
            return null;
        }
        boolean secondarySuccess =
                o.status() == PaymentStatus.SUCCEEDED
                        && r.getSettledPaymentId() != null
                        && (o.metadata() == null || o.metadata().reviewReason() == null);
        if (o.providerTransactionId() == null
                || o.providerTransactionId().isBlank()
                || o.providerTransactionId().length() > 160
                || (p.getProviderTransactionId() != null
                        && !p.getProviderTransactionId().equals(o.providerTransactionId())
                        && !secondarySuccess)) {
            return "TRANSACTION_MISMATCH";
        }
        if (secondarySuccess && !o.providerTransactionId().matches("[A-Za-z0-9_-]{1,160}"))
            return "TRANSACTION_MISMATCH";
        store.coordinate(
                "transaction:"
                        + p.getProvider()
                        + ":"
                        + p.getProviderAccountReference()
                        + ":"
                        + o.providerTransactionId());
        if (refunds.isRefundTransaction(
                p.getProvider(), p.getProviderAccountReference(), o.providerTransactionId()))
            return "TRANSACTION_MISMATCH";
        var existing =
                store.byProviderTransaction(
                        p.getProvider(),
                        p.getProviderAccountReference(),
                        o.providerTransactionId());
        if (existing.isPresent() && !existing.get().getId().equals(p.getId())) {
            return "TRANSACTION_MISMATCH";
        }
        var incident =
                reviews.byTransaction(
                        p.getProvider(),
                        p.getProviderAccountReference(),
                        o.providerTransactionId());
        if (incident.isPresent()
                && (!incident.get().getAnomalousPaymentId().equals(p.getId())
                        || !incident.get().getPaymentRequestId().equals(r.getId())))
            return "TRANSACTION_MISMATCH";
        return null;
    }

    private String apply(PaymentRequest r, Payment p, ProviderObservation o) {
        return apply(r, p, o, clock.instant());
    }

    private String apply(PaymentRequest r, Payment p, ProviderObservation o, Instant now) {
        boolean review = o != null && o.metadata() != null && o.metadata().reviewReason() != null;
        if (!review
                && p.getStatus() == PaymentStatus.SUCCEEDED
                && o != null
                && o.status() != PaymentStatus.SUCCEEDED) {
            return null;
        }
        String error = validate(r, p, o);
        p.checked(now);
        if (error != null) {
            p.review(error);
            return error;
        }
        if (review) {
            p.retainVerifiedTransaction(o);
            p.review(o.metadata().reviewReason().name());
            return p.getReconciliationReason();
        }
        if ("PROVIDER_REVERSAL".equals(p.getReconciliationReason())) {
            p.retainVerifiedTransaction(o);
            return p.getReconciliationReason();
        }
        if (o.status() == PaymentStatus.SUCCEEDED
                && r.getSettledPaymentId() != null
                && (!r.getSettledPaymentId().equals(p.getId())
                        || !Objects.equals(
                                p.getProviderTransactionId(), o.providerTransactionId()))) {
            var incident =
                    reviews.byTransaction(
                                    o.provider(),
                                    o.providerAccountReference(),
                                    o.providerTransactionId())
                            .orElse(null);
            if (incident == null) {
                incident = PaymentReview.duplicate(r, p, o, now);
                // Preserve an already bound transaction, even on a failed attempt.
                if (p.getProviderTransactionId() == null
                        || p.getProviderTransactionId().equals(o.providerTransactionId()))
                    p.apply(o, now);
                reviews.add(incident);
            }
            if (incident.getStatus() == PaymentReviewStatus.OPEN) p.markDuplicateReview();
            store.flush();
            return PaymentReview.DUPLICATE;
        }
        p.retainVerifiedTransaction(o);
        boolean firstSuccess = p.apply(o, now);
        if (firstSuccess) {
            UUID oldSettlement = r.getSettledPaymentId();
            String reason = r.allocate(p.getId(), now);
            if (reason != null) {
                p.review(reason);
                return reason;
            }
            if (oldSettlement == null) {
                events.publishEvent(
                        new PaymentRequestSettled(
                                UUID.randomUUID(),
                                p.getConfirmedAt(),
                                r.getOrganizationId(),
                                r.getOrderId(),
                                r.getId(),
                                p.getId(),
                                r.getPurpose(),
                                r.getExpectedAmount(),
                                r.getCurrency()));
            }
        }
        return p.isReconciliationRequired() ? p.getReconciliationReason() : null;
    }

    public UUID receive(
            String provider,
            String account,
            String eventId,
            String key,
            String fingerprint,
            boolean authentic,
            ProviderObservation observation,
            String parsingError) {
        store.coordinate("webhook:" + key);
        var old = store.byWebhookKey(key);
        if (old.isPresent()) {
            if (parsingError != null) {
                store.lockWebhook(old.get().getId()).parsingFailed(parsingError, clock.instant());
            }
            return old.get().getId();
        }
        // New correlatable admissions share the obligation lock with Completion.
        // Deduplication returns above; no existing receipt lock is acquired after this lock.
        if (authentic && observation != null && observation.merchantReference() != null) {
            store.byMerchant(observation.merchantReference())
                    .filter(
                            p ->
                                    p.getProvider().equals(provider)
                                            && Objects.equals(
                                                    p.getProviderAccountReference(), account))
                    .ifPresent(p -> store.lockRequest(p.getPaymentRequestId()));
        }
        var event =
                PaymentWebhookEvent.receive(
                        provider,
                        account,
                        eventId,
                        key,
                        fingerprint,
                        authentic,
                        observation,
                        clock.instant());
        if (parsingError != null) {
            event.parsingFailed(parsingError, clock.instant());
        }
        store.add(event);
        store.flush();
        return event.getId();
    }

    public void webhookFailure(UUID id) {
        var event = store.lockWebhook(id);
        if (event.pending()
                && (event.getNextAttemptAt() == null
                        || !clock.instant().isBefore(event.getNextAttemptAt()))) {
            event.retry("PROCESSING_FAILURE", clock.instant());
        }
    }

    public record WebhookWork(
            String provider,
            ProviderObservation observation,
            ProviderInstruction instruction,
            boolean pending) {}

    public WebhookWork prepareWebhook(UUID id) {
        var e = store.webhook(id).orElseThrow();
        var p = resolved(e).filter(candidate -> matchesReceipt(e, candidate));
        var instruction =
                p.isEmpty()
                        ? null
                        : p.get().instruction(store.lockRequest(p.get().getPaymentRequestId()));
        return new WebhookWork(
                e.getProvider(),
                e.getObservation(),
                instruction,
                e.pending()
                        && (e.getNextAttemptAt() == null
                                || !clock.instant().isBefore(e.getNextAttemptAt())));
    }

    private Optional<Payment> resolved(PaymentWebhookEvent e) {
        var o = e.getObservation();
        if (o == null || o.merchantReference() == null) {
            return Optional.empty();
        }
        return store.byMerchant(o.merchantReference());
    }

    private boolean matchesReceipt(PaymentWebhookEvent e, Payment p) {
        return p.getProvider().equals(e.getProvider())
                && Objects.equals(p.getProviderAccountReference(), e.getProviderAccountReference());
    }

    public void processWebhook(UUID id, ProviderObservation verified, boolean verificationFailed) {
        var e = store.lockWebhook(id);
        if (!e.pending()
                || (e.getNextAttemptAt() != null
                        && clock.instant().isBefore(e.getNextAttemptAt()))) {
            return;
        }
        if (!"VALID".equals(e.getAuthenticityVerificationResult())) {
            e.finish("REVIEW_REQUIRED", "PAYMENT_AUTHENTICITY_FAILED", clock.instant());
            return;
        }
        var candidate = resolved(e);
        if (candidate.isEmpty()) {
            e.finish("REVIEW_REQUIRED", "UNKNOWN_REFERENCE", clock.instant());
            return;
        }
        var r = store.lockRequest(candidate.get().getPaymentRequestId());
        var p = store.lockPayment(candidate.get().getId());
        if (!matchesReceipt(e, p)) {
            e.finish("REVIEW_REQUIRED", "PROVIDER_MISMATCH", clock.instant());
            return;
        }
        e.resolve(p);
        var raw = e.getObservation().correlate(p.instruction(r));
        if (verified != null) {
            verified = verified.correlate(p.instruction(r));
        }
        if (p.getStatus() == PaymentStatus.SUCCEEDED
                && raw.status() != PaymentStatus.SUCCEEDED
                && (raw.metadata() == null || raw.metadata().reviewReason() == null)) {
            e.finish("IGNORED", null, clock.instant());
            return;
        }
        String invalid = validate(r, p, raw);
        if (invalid != null) {
            p.review(invalid);
            e.finish("REVIEW_REQUIRED", invalid, clock.instant());
            return;
        }
        // One decision instant for eligibility and allocation prevents crossing the deadline
        // between them.
        Instant decisionAt = clock.instant();
        if (raw.status() == PaymentStatus.SUCCEEDED) {
            if (providers.acceptsAuthenticatedWebhook(
                    e.getProvider(), e.getProviderAccountReference())) {
                String rejection = null;
                if (p.getPaymentReference() == null
                        || raw.metadata() == null
                        || raw.metadata().paymentReference() == null
                        || !p.getPaymentReference().equals(raw.metadata().paymentReference())) {
                    rejection = "PAYMENT_REFERENCE_MISMATCH";
                } else if (!raw.providerTransactionId().matches("[A-Za-z0-9_-]{1,160}")) {
                    rejection = "TRANSACTION_MISMATCH";
                } else if (r.getSettledPaymentId() == null) {
                    rejection = r.settlementRejection(p.getId(), decisionAt);
                }
                if (rejection != null) {
                    p.review(rejection);
                    e.finish("REVIEW_REQUIRED", rejection, clock.instant());
                    return;
                }
            } else if (verificationFailed
                    || verified == null
                    || verified.status() != PaymentStatus.SUCCEEDED
                    || !Objects.equals(
                            raw.providerTransactionId(), verified.providerTransactionId())
                    || validate(r, p, verified) != null) {
                if (p.getStatus() != PaymentStatus.SUCCEEDED) {
                    p.review("PROVIDER_STATE_UNCERTAIN");
                }
                e.retry("PROVIDER_STATE_UNCERTAIN", clock.instant());
                return;
            }
        }
        String result = apply(r, p, raw, decisionAt);
        e.finish(result == null ? "PROCESSED" : "REVIEW_REQUIRED", result, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<UUID> retryableWebhooks(int limit) {
        return store.retryableWebhooks(clock.instant(), limit);
    }

    @Transactional(readOnly = true)
    public List<UUID> uncertainPayments(int limit) {
        return store.uncertainPayments(limit);
    }

    @Transactional(readOnly = true)
    public PaymentRequestView request(UUID org, UUID id) {
        return scopedRequest(org, id).view();
    }

    @Transactional(readOnly = true)
    public List<PaymentObligationView> shippingRequests(UUID org, UUID order) {
        return store.shippingRequests(org, order).stream().map(this::obligation).toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<PaymentObligationView> lockShippingRequests(UUID org, UUID order) {
        return store.shippingRequests(org, order).stream()
                .map(r -> obligation(store.lockRequest(r.getId())))
                .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelShippingRequest(UUID org, UUID id) {
        var r = scopedRequest(org, id);
        r = store.lockRequest(r.getId());
        if (r.getPurpose() != PaymentPurpose.SHIPPING_BALANCE)
            throw new IllegalArgumentException("Expected shipping obligation");
        if (obligation(r).paymentInProgress())
            throw new ApiException(
                    409,
                    "SHIPPING_BALANCE_PAYMENT_IN_PROGRESS",
                    "Un paiement du transport est en cours de traitement. Attendez sa confirmation ou sa réconciliation avant de corriger le montant.");
        r.cancel(clock.instant());
        store.flush();
    }

    private PaymentObligationView obligation(PaymentRequest r) {
        boolean blocking =
                !PaymentRisk.reasons(r, store.attempts(r.getId()), reviews.forRequest(r.getId()))
                        .isEmpty();
        return new PaymentObligationView(r.view(), blocking);
    }

    private PaymentRequest scopedRequest(UUID org, UUID id) {
        return store.request(id)
                .filter(r -> r.getOrganizationId().equals(org))
                .orElseThrow(
                        () ->
                                new ApiException(
                                        404,
                                        "PAYMENT_REQUEST_NOT_FOUND",
                                        "Obligation introuvable."));
    }

    private PaymentRequest initialEntity(UUID org, UUID order) {
        return store.initial(org, order)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        404,
                                        "PAYMENT_REQUEST_NOT_FOUND",
                                        "Obligation introuvable."));
    }
}
