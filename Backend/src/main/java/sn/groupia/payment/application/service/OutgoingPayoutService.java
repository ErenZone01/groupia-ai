package sn.groupia.payment.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.payment.api.OutgoingPayoutOperations;
import sn.groupia.payment.domain.service.OutgoingPayoutGateway;
import sn.groupia.payment.infrastructure.persistence.OutgoingPayoutStore;

@Service
public class OutgoingPayoutService implements OutgoingPayoutOperations {
    private final OutgoingPayoutStore store;
    private final OutgoingPayoutGateway gateway;

    public OutgoingPayoutService(OutgoingPayoutStore store, OutgoingPayoutGateway gateway) {
        this.store = store;
        this.gateway = gateway;
    }

    public boolean available() {
        return gateway.enabled();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void register(Registration r) {
        String prefix = r.operationType() == Kind.REFUND ? "GPR-" : "MERCHANT_PAYOUT:";
        if (!r.businessReference().startsWith(prefix))
            throw new IllegalArgumentException("PAYOUT_REFERENCE_NAMESPACE_INVALID");
        store.register(r);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String bind(Kind kind, UUID id, String tx) {
        return store.bind(kind, id, tx);
    }

    @Transactional(propagation = Propagation.NEVER)
    public String initiate(Kind kind, UUID id, Recipient recipient) {
        var r = store.find(kind, id).orElseThrow().registration();
        return gateway.initiate(r, recipient);
    }

    @Transactional(propagation = Propagation.NEVER)
    public Evidence verify(Kind kind, UUID id) {
        var r = store.find(kind, id).orElseThrow();
        if (r.transactionId() == null)
            throw new IllegalStateException("PAYOUT_REFERENCE_UNAVAILABLE");
        var e = gateway.verify(r.registration(), r.transactionId());
        UUID receipt =
                store.receipt(
                        hash(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)),
                        true,
                        r,
                        e,
                        e.errorCode());
        return store.evidence(receipt);
    }

    public Evidence readEvidence(UUID id) {
        return store.evidence(id);
    }

    public void acknowledgeEvidence(UUID id, String error) {
        store.acknowledge(id, error);
        if (error != null)
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("Outgoing payout review receiptId={} code={}", id, error);
    }

    public void incident(Kind kind, UUID id, String error) {
        var r = store.find(kind, id).orElseThrow();
        var receipt =
                store.receipt(
                        hash(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)),
                        true,
                        r,
                        null,
                        error);
        org.slf4j.LoggerFactory.getLogger(getClass())
                .warn("Outgoing payout incident receiptId={} code={}", receipt, error);
    }

    public boolean recognizes(String provider, byte[] payload) {
        if (!"BICTORYS".equalsIgnoreCase(provider)) return false;
        var hint = gateway.identify(payload);
        if (hint.reference() != null && hint.reference().startsWith("GP-")) return false;
        return hint.reference() != null && store.reference(hint.reference()).isPresent()
                || hint.transactionId() != null
                        && store.transaction(hint.transactionId()).isPresent()
                || hint.outgoingHint();
    }

    @Transactional(propagation = Propagation.NEVER)
    public UUID receive(byte[] payload, Map<String, String> headers) {
        boolean authentic = gateway.authentic(payload, headers);
        var hint = gateway.identify(payload);
        var owner =
                hint.reference() == null
                        ? Optional.<OutgoingPayoutStore.Stored>empty()
                        : store.reference(hint.reference());
        if (owner.isEmpty() && hint.transactionId() != null)
            owner = store.transaction(hint.transactionId());
        Evidence e = null;
        String error = null;
        if (!authentic) error = "PAYMENT_AUTHENTICITY_FAILED";
        else if (owner.isEmpty()) error = "UNKNOWN_REFERENCE";
        else
            try {
                e = gateway.parse(owner.get().registration(), payload);
                error = e.errorCode();
            } catch (RuntimeException ex) {
                error = safeCode(ex);
                var r = owner.orElseThrow().registration();
                e =
                        new Evidence(
                                null,
                                r.operationType(),
                                r.operationId(),
                                r.provider(),
                                r.account(),
                                r.businessReference(),
                                hint.transactionId(),
                                null,
                                r.currency(),
                                r.method(),
                                null,
                                "REVIEW_REQUIRED",
                                null,
                                null,
                                true,
                                error);
            }
        UUID receipt = store.receipt(hash(payload), authentic, owner.orElse(null), e, error);
        // The receipt and any event publication are committed before acknowledging Bictorys.
        // Unauthenticated receipts never publish financial evidence.
        return receipt;
    }

    private static String safeCode(RuntimeException ex) {
        return Set.of(
                                "PAYOUT_AMOUNT_SEMANTICS_UNCONFIRMED",
                                "PAYOUT_INVALID_TIMESTAMP",
                                "PAYOUT_RECIPIENT_MISMATCH",
                                "PAYOUT_DIRECTION_MISMATCH",
                                "PAYOUT_INCOMPLETE_EVIDENCE",
                                "PAYOUT_UNKNOWN_STATUS",
                                "PAYOUT_REFERENCE_MISMATCH",
                                "PAYOUT_CURRENCY_MISMATCH",
                                "PAYOUT_METHOD_MISMATCH",
                                "PAYOUT_INVALID_JSON")
                        .contains(Objects.toString(ex.getMessage(), ""))
                ? ex.getMessage()
                : "PAYOUT_INVALID_EVIDENCE";
    }

    private static String hash(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
