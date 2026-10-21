package sn.groupia.ordering.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Merchant-facing value of accepted goods orders, not payouts, provider cash or net profit. */
@Schema(
        description =
                "Gross historical goods value of CONFIRMED and COMPLETED orders, before refunds or financial corrections. Excludes platform and shipping fees. Not net profit, provider cash or available payouts.")
public record BusinessAnalyticsView(
        String currency,
        String timezone,
        Instant periodStart,
        Instant periodEndExclusive,
        Instant comparisonStart,
        Instant comparisonEndExclusive,
        BigDecimal goodsSales,
        long confirmedOrders,
        BigDecimal averageBasket,
        BigDecimal previousYearGoodsSales,
        long previousYearConfirmedOrders,
        BigDecimal goodsSalesChangePercent,
        BigDecimal confirmedOrdersChangePercent,
        List<MonthlyPoint> months) {

    public record MonthlyPoint(
            String month,
            long confirmedOrders,
            BigDecimal goodsSales,
            long previousYearConfirmedOrders,
            BigDecimal previousYearGoodsSales) {}
}
