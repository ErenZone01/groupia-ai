package sn.groupia.settlement.infrastructure.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import sn.groupia.payment.api.OutgoingPayoutOperations;
import sn.groupia.payment.api.OutgoingPayoutOperations.Kind;
import sn.groupia.settlement.domain.model.MerchantPayout;
import sn.groupia.settlement.domain.service.MerchantPayoutProvider;

@Component
@ConditionalOnProperty(
        name = "groupia.settlement.payout-provider",
        havingValue = "BICTORYS",
        matchIfMissing = true)
public class PaymentOutgoingMerchantPayoutProvider implements MerchantPayoutProvider {
    private final OutgoingPayoutOperations outgoing;

    public PaymentOutgoingMerchantPayoutProvider(OutgoingPayoutOperations outgoing) {
        this.outgoing = outgoing;
    }

    public boolean enabled() {
        return outgoing.available();
    }

    public String name() {
        return "BICTORYS";
    }

    public String account() {
        return "sandbox";
    }

    public String initiate(MerchantPayout p) {
        return outgoing.initiate(
                Kind.MERCHANT_PAYOUT,
                p.id(),
                new OutgoingPayoutOperations.Recipient(p.holderName(), p.phoneE164()));
    }

    public boolean publishesEvidence() {
        return true;
    }

    public Proof verify(MerchantPayout p) {
        // The durable receipt event applies the proof. Do not discard diagnostics in a second path.
        outgoing.verify(Kind.MERCHANT_PAYOUT, p.id());
        return null;
    }
}
