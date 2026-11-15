package sn.groupia.settlement.application.service;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import sn.groupia.payment.api.event.PayoutStatusNotificationReceived;
import sn.groupia.settlement.domain.repository.MerchantFinanceStore;

@Component
public class MerchantPayoutNotificationListener {
    private final MerchantFinanceStore store;

    public MerchantPayoutNotificationListener(MerchantFinanceStore store) {
        this.store = store;
    }

    @ApplicationModuleListener
    public void received(PayoutStatusNotificationReceived event) {
        // A notification cannot bind an unknown ID, overwrite a status or release funds.
        // If it precedes the ACK, periodic reconciliation takes over after ID binding.
        store.requestVerification(
                event.provider(), event.account(), event.transactionId().toString());
    }
}
