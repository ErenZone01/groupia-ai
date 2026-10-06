package sn.groupia.settlement.infrastructure.provider;
import org.springframework.stereotype.Component;
import sn.groupia.settlement.domain.service.MerchantPayoutProvider;
import sn.groupia.settlement.domain.model.MerchantPayout;
/** No credentials, network calls, fake success or real Bictorys settlement adapter in this tranche. */
@Component
class UnavailableMerchantPayoutProvider implements MerchantPayoutProvider {
    public boolean enabled() { return false; }
    public String name() { return "BICTORYS"; }
    public String account() { return "sandbox"; }
    public String initiate(MerchantPayout payout) { throw new IllegalStateException("Merchant payout unavailable"); }
    public Proof verify(MerchantPayout payout) { throw new IllegalStateException("Merchant payout unavailable"); }
}
