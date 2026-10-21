package sn.groupia.ordering.application.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Tenant-scoped, database-aggregated monthly facts from commercially accepted orders. */
public interface BusinessAnalyticsReadModel {
    List<MonthlyTotals> monthly(UUID organizationId, Instant fromInclusive, Instant toExclusive);

    record MonthlyTotals(String month, long confirmedOrders, BigDecimal goodsSales) {}
}
