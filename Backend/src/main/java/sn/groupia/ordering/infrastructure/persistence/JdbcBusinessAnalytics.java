package sn.groupia.ordering.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import sn.groupia.ordering.application.query.BusinessAnalyticsReadModel;

/** The status and date predicates must remain identical for current and comparison periods. */
@Repository
class JdbcBusinessAnalytics implements BusinessAnalyticsReadModel {
    private static final String MONTHLY_SQL =
            """
            SELECT to_char(confirmed_at AT TIME ZONE 'Africa/Dakar', 'YYYY-MM') AS month,
                   count(*) AS confirmed_orders,
                   sum(goods_subtotal) AS goods_sales
              FROM orders
             WHERE organization_id = ?
               AND currency = 'XOF'
               AND status IN ('CONFIRMED', 'COMPLETED')
               AND confirmed_at IS NOT NULL
               AND confirmed_at >= ?
               AND confirmed_at < ?
             GROUP BY 1
             ORDER BY 1
            """;

    private final JdbcTemplate jdbc;

    JdbcBusinessAnalytics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<MonthlyTotals> monthly(
            UUID organizationId, Instant fromInclusive, Instant toExclusive) {
        return jdbc.query(
                MONTHLY_SQL,
                (rs, row) ->
                        new MonthlyTotals(
                                rs.getString("month"),
                                rs.getLong("confirmed_orders"),
                                rs.getBigDecimal("goods_sales")),
                organizationId,
                Timestamp.from(fromInclusive),
                Timestamp.from(toExclusive));
    }
}
