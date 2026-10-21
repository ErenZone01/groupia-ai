package sn.groupia.ordering.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.ordering.api.dto.BusinessAnalyticsView;
import sn.groupia.ordering.application.query.BusinessAnalyticsReadModel;
import sn.groupia.ordering.application.query.BusinessAnalyticsReadModel.MonthlyTotals;
import sn.groupia.organization.api.OrganizationAccess;
import sn.groupia.organization.api.OrganizationPermission;
import sn.groupia.shared.api.ApiException;

@Service
public class BusinessAnalyticsService {
    private static final ZoneId DAKAR = ZoneId.of("Africa/Dakar");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private final BusinessAnalyticsReadModel readModel;
    private final OrganizationAccess access;
    private final Clock clock;

    BusinessAnalyticsService(
            BusinessAnalyticsReadModel readModel, OrganizationAccess access, Clock clock) {
        this.readModel = readModel;
        this.access = access;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BusinessAnalyticsView overview(UUID organizationId, int months) {
        access.requirePermission(organizationId, OrganizationPermission.VIEW_ORDERS);
        if (months != 3 && months != 6 && months != 12) {
            throw new ApiException(400, "INVALID_REQUEST", "Période analytique invalide.");
        }

        var now = clock.instant().atZone(DAKAR);
        var firstMonth = YearMonth.from(now).minusMonths(months - 1L);
        Instant start = firstMonth.atDay(1).atStartOfDay(DAKAR).toInstant();
        Instant end = now.toInstant();
        Instant comparisonStart =
                firstMonth.minusYears(1).atDay(1).atStartOfDay(DAKAR).toInstant();
        Instant comparisonEnd = now.minusYears(1).toInstant();

        Map<String, MonthlyTotals> current =
                index(readModel.monthly(organizationId, start, end));
        Map<String, MonthlyTotals> previous =
                index(readModel.monthly(organizationId, comparisonStart, comparisonEnd));
        var points = new ArrayList<BusinessAnalyticsView.MonthlyPoint>(months);
        BigDecimal sales = BigDecimal.ZERO;
        BigDecimal previousSales = BigDecimal.ZERO;
        long count = 0L;
        long previousCount = 0L;

        for (int i = 0; i < months; i++) {
            YearMonth month = firstMonth.plusMonths(i);
            MonthlyTotals currentPoint = current.get(month.toString());
            MonthlyTotals previousPoint = previous.get(month.minusYears(1).toString());
            BigDecimal monthSales =
                    currentPoint == null ? BigDecimal.ZERO : currentPoint.goodsSales();
            BigDecimal previousMonthSales =
                    previousPoint == null ? BigDecimal.ZERO : previousPoint.goodsSales();
            long monthCount = currentPoint == null ? 0L : currentPoint.confirmedOrders();
            long previousMonthCount =
                    previousPoint == null ? 0L : previousPoint.confirmedOrders();
            points.add(
                    new BusinessAnalyticsView.MonthlyPoint(
                            month.toString(),
                            monthCount,
                            monthSales,
                            previousMonthCount,
                            previousMonthSales));
            sales = sales.add(monthSales);
            previousSales = previousSales.add(previousMonthSales);
            count += monthCount;
            previousCount += previousMonthCount;
        }

        return new BusinessAnalyticsView(
                "XOF",
                DAKAR.getId(),
                start,
                end,
                comparisonStart,
                comparisonEnd,
                sales,
                count,
                count == 0
                        ? null
                        : sales.divide(BigDecimal.valueOf(count), 0, RoundingMode.HALF_UP),
                previousSales,
                previousCount,
                percentage(sales, previousSales),
                percentage(BigDecimal.valueOf(count), BigDecimal.valueOf(previousCount)),
                List.copyOf(points));
    }

    private static Map<String, MonthlyTotals> index(java.util.List<MonthlyTotals> facts) {
        return facts.stream().collect(Collectors.toMap(MonthlyTotals::month, Function.identity()));
    }

    private static BigDecimal percentage(BigDecimal current, BigDecimal previous) {
        if (previous.signum() == 0) {
            return null;
        }
        return current.subtract(previous)
                .multiply(HUNDRED)
                .divide(previous, 1, RoundingMode.HALF_UP);
    }
}
