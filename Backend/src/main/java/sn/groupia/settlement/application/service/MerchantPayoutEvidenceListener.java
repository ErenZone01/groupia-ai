package sn.groupia.settlement.application.service;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import sn.groupia.payment.api.OutgoingPayoutOperations;
import sn.groupia.payment.api.event.OutgoingPayoutEvidenceReceived;

@Component
public class MerchantPayoutEvidenceListener {
    private final OutgoingPayoutOperations outgoing;
    private final MerchantPayoutTransactions payouts;

    public MerchantPayoutEvidenceListener(
            OutgoingPayoutOperations outgoing, MerchantPayoutTransactions payouts) {
        this.outgoing = outgoing;
        this.payouts = payouts;
    }

    @ApplicationModuleListener
    public void received(OutgoingPayoutEvidenceReceived event) {
        if (event.operationType() != OutgoingPayoutOperations.Kind.MERCHANT_PAYOUT) return;
        var e = outgoing.readEvidence(event.receiptId());
        if (e == null
                || !event.operationId().equals(e.operationId())
                || e.operationType() != event.operationType())
            throw new IllegalStateException("Payout evidence identity mismatch");
        payouts.evidence(event.organizationId(), event.operationId(), e);
    }
}
