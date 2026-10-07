package sn.groupia.grouping.api;
import java.util.UUID;
/** Trusted settlement coordinator API; requires caller authorization. */
public interface MerchantCampaignFacts {
    Fact read(UUID organizationId, UUID campaignId);
    Fact lock(UUID organizationId, UUID campaignId);
    record Fact(UUID id, String title, String status) {
        public boolean available() { return java.util.Set.of("CLOSED","SUPPLIER_ORDERED","IN_TRANSIT","ARRIVED","DISTRIBUTING","COMPLETED").contains(status); }
    }
}
