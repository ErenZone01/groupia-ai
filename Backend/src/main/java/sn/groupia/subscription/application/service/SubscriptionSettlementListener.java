package sn.groupia.subscription.application.service;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.event.PaymentObligationSettled;

@Component
public class SubscriptionSettlementListener {
    private final SubscriptionPaymentRecorder recorder;

    SubscriptionSettlementListener(SubscriptionPaymentRecorder recorder) {
        this.recorder = recorder;
    }

    @ApplicationModuleListener
    public void on(PaymentObligationSettled event) {
        if (event.target().type() != PaymentTargetType.SUBSCRIPTION_INVOICE
                || event.purpose() != PaymentPurpose.SUBSCRIPTION_MONTHLY) return;
        recorder.record(event);
    }
}
