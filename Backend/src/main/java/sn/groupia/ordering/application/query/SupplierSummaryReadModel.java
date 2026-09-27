package sn.groupia.ordering.application.query;
import java.time.Instant;
import java.util.UUID;
import sn.groupia.ordering.api.dto.*;
public interface SupplierSummaryReadModel {
    SupplierSummaryView read(UUID organizationId, UUID campaignId);
    SupplierProjection projection(UUID organizationId, UUID campaignId, Instant decisionAt);
    void lockOrders(UUID organizationId, UUID campaignId);
}
