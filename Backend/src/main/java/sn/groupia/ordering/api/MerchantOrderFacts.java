package sn.groupia.ordering.api;
import java.util.UUID;
/** Trusted financial consumer API. Caller owns authorization; no customer data. */
public interface MerchantOrderFacts {
    Fact read(UUID organizationId, UUID orderId);
    record Fact(UUID organizationId, UUID campaignId, UUID orderId, String status) {
        public boolean accepted() { return status.equals("CONFIRMED") || status.equals("COMPLETED"); }
    }
}
