package sn.groupia.ordering.application.service;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.ordering.api.MerchantOrderFacts;
import sn.groupia.ordering.domain.repository.Orders;
@Service @Transactional(readOnly=true)
class MerchantOrderFactsService implements MerchantOrderFacts {
    private final Orders orders;
    MerchantOrderFactsService(Orders orders) { this.orders=orders; }
    public Fact read(UUID org, UUID id) {
        var o=orders.find(org,id).orElseThrow(()->new IllegalStateException("Merchant order missing"));
        return new Fact(org,o.getCampaignId(),id,o.getStatus().name());
    }
}
