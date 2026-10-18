package sn.groupia.payment.application.service;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import sn.groupia.payment.api.OutgoingPayoutOperations;
import sn.groupia.payment.api.event.OutgoingPayoutEvidenceReceived;

@Component
public class OutgoingRefundEvidenceListener {
    private final OutgoingPayoutOperations outgoing;
    private final RefundTransactions refunds;

    public OutgoingRefundEvidenceListener(
            OutgoingPayoutOperations outgoing, RefundTransactions refunds) {
        this.outgoing = outgoing;
        this.refunds = refunds;
    }

    @ApplicationModuleListener
    public void received(OutgoingPayoutEvidenceReceived event) {
        if (event.operationType() != OutgoingPayoutOperations.Kind.REFUND) return;
        var proof = outgoing.readEvidence(event.receiptId());
        if (proof == null
                || !event.operationId().equals(proof.operationId())
                || proof.operationType() != event.operationType())
            throw new IllegalStateException("Payout evidence identity mismatch");
        refunds.evidence(proof);
    }
}
