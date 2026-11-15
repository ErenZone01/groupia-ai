package sn.groupia.settlement.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sn.groupia.settlement.application.service.MerchantSettlementService;

@Component
@ConditionalOnProperty(
        name = "groupia.settlement.saspay-payout-reconciliation.enabled",
        havingValue = "true")
public class MerchantPayoutReconciliationSchedule {
    private final MerchantSettlementService settlements;

    public MerchantPayoutReconciliationSchedule(MerchantSettlementService settlements) {
        this.settlements = settlements;
    }

    @Scheduled(
            fixedDelayString = "${groupia.settlement.saspay-payout-reconciliation.delay-ms:60000}",
            initialDelayString =
                    "${groupia.settlement.saspay-payout-reconciliation.delay-ms:60000}")
    public void reconcile() {
        settlements.reconcilePending();
    }
}
