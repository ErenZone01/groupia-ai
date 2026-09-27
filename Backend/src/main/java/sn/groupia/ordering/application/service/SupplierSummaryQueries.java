package sn.groupia.ordering.application.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.grouping.api.GroupingQueries;
import sn.groupia.ordering.api.dto.SupplierSummaryView;
import sn.groupia.ordering.application.query.SupplierSummaryReadModel;
import sn.groupia.organization.api.OrganizationAccess;
import sn.groupia.organization.api.OrganizationPermission;

@Service
@Transactional(readOnly = true)
public class SupplierSummaryQueries {
    private final OrganizationAccess access;
    private final GroupingQueries grouping;
    private final SupplierSummaryReadModel summaries;

    public SupplierSummaryQueries(OrganizationAccess access, GroupingQueries grouping, SupplierSummaryReadModel summaries) {
        this.access = access;
        this.grouping = grouping;
        this.summaries = summaries;
    }

    public SupplierSummaryView get(UUID organizationId, UUID campaignId) {
        access.requirePermission(organizationId, OrganizationPermission.VIEW_ORDERS);
        grouping.requireCampaignBelongsTo(organizationId, campaignId);
        return summaries.read(organizationId, campaignId);
    }
}
