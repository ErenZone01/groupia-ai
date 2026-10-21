package sn.groupia.ordering.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import sn.groupia.ordering.application.query.BusinessAnalyticsReadModel;
import sn.groupia.ordering.application.query.BusinessAnalyticsReadModel.MonthlyTotals;
import sn.groupia.organization.api.OrganizationAccess;
import sn.groupia.organization.api.OrganizationPermission;
import sn.groupia.shared.api.ApiException;

class BusinessAnalyticsServiceTest {
    private final BusinessAnalyticsReadModel readModel = mock(BusinessAnalyticsReadModel.class);
    private final OrganizationAccess access = mock(OrganizationAccess.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T02:00:00Z"), ZoneOffset.UTC);
    private final BusinessAnalyticsService service =
            new BusinessAnalyticsService(readModel, access, clock);
    private final UUID org = UUID.randomUUID();

    @Test
    void alignsPartialMonthWithSameInstantPreviousYearAndExcludesFees() {
        Instant start = Instant.parse("2026-08-01T00:00:00Z");
        Instant comparisonStart = Instant.parse("2025-08-01T00:00:00Z");
        when(readModel.monthly(org, start, clock.instant()))
                .thenReturn(
                        List.of(
                                new MonthlyTotals("2026-09", 1, new BigDecimal("1000")),
                                new MonthlyTotals("2026-10", 2, new BigDecimal("2500"))));
        when(readModel.monthly(org, comparisonStart, Instant.parse("2025-10-21T02:00:00Z")))
                .thenReturn(List.of(new MonthlyTotals("2025-09", 1, new BigDecimal("2500"))));

        var view = service.overview(org, 3);

        verify(access).requirePermission(org, OrganizationPermission.VIEW_ORDERS);
        assertThat(view.currency()).isEqualTo("XOF");
        assertThat(view.timezone()).isEqualTo("Africa/Dakar");
        assertThat(view.periodStart()).isEqualTo(start);
        assertThat(view.comparisonStart()).isEqualTo(comparisonStart);
        assertThat(view.comparisonEndExclusive()).isEqualTo(Instant.parse("2025-10-21T02:00:00Z"));
        assertThat(view.goodsSales()).isEqualByComparingTo("3500");
        assertThat(view.confirmedOrders()).isEqualTo(3);
        assertThat(view.averageBasket()).isEqualByComparingTo("1167");
        assertThat(view.previousYearGoodsSales()).isEqualByComparingTo("2500");
        assertThat(view.previousYearConfirmedOrders()).isEqualTo(1);
        assertThat(view.goodsSalesChangePercent()).isEqualByComparingTo("40.0");
        assertThat(view.confirmedOrdersChangePercent()).isEqualByComparingTo("200.0");
        assertThat(view.months()).hasSize(3);
        assertThat(view.months().get(0).month()).isEqualTo("2026-08");
        assertThat(view.months().get(0).goodsSales()).isEqualByComparingTo("0");
        assertThat(view.months().get(1).previousYearGoodsSales()).isEqualByComparingTo("2500");
    }

    @Test
    void missingSalesProduceHonestZerosAndNoInventedAverageOrPercentage() {
        when(readModel.monthly(eq(org), any(), any())).thenReturn(List.of());
        var view = service.overview(org, 12);

        assertThat(view.months()).hasSize(12);
        assertThat(view.goodsSales()).isEqualByComparingTo("0");
        assertThat(view.confirmedOrders()).isZero();
        assertThat(view.averageBasket()).isNull();
        assertThat(view.goodsSalesChangePercent()).isNull();
        assertThat(view.confirmedOrdersChangePercent()).isNull();
        assertThat(view.months()).allSatisfy(point -> assertThat(point.confirmedOrders()).isZero());
    }

    @Test
    void refusesAnalyticsBeforeReadingAnyOtherOrganizationData() {
        doThrow(new ApiException(403, "FORBIDDEN", "Accès refusé."))
                .when(access)
                .requirePermission(org, OrganizationPermission.VIEW_ORDERS);

        assertThatThrownBy(() -> service.overview(org, 12))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(403);
        verifyNoInteractions(readModel);
    }

    @Test
    void rejectsUnboundedPeriods() {
        assertThatThrownBy(() -> service.overview(org, 24))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(400);
        verify(access).requirePermission(org, OrganizationPermission.VIEW_ORDERS);
    }

    @ParameterizedTest
    @CsvSource({
        "2026-01-01T00:00:00Z,3,2025-11-01T00:00:00Z,2024-11-01T00:00:00Z,2025-01-01T00:00:00Z",
        "2026-01-31T23:59:59Z,6,2025-08-01T00:00:00Z,2024-08-01T00:00:00Z,2025-01-31T23:59:59Z",
        "2024-02-29T12:30:00Z,12,2023-03-01T00:00:00Z,2022-03-01T00:00:00Z,2023-02-28T12:30:00Z",
        "2025-03-01T00:00:00Z,3,2025-01-01T00:00:00Z,2024-01-01T00:00:00Z,2024-03-01T00:00:00Z"
    })
    void boundsPeriodsAcrossYearsAndLeapDaysInDakar(
            String now, int months, String start, String previousStart, String previousEnd) {
        var localClock = Clock.fixed(Instant.parse(now), ZoneOffset.ofHours(12));
        when(readModel.monthly(eq(org), any(), any())).thenReturn(List.of());
        var view =
                new BusinessAnalyticsService(readModel, access, localClock).overview(org, months);
        assertThat(view.periodStart()).isEqualTo(Instant.parse(start));
        assertThat(view.periodEndExclusive()).isEqualTo(Instant.parse(now));
        assertThat(view.comparisonStart()).isEqualTo(Instant.parse(previousStart));
        assertThat(view.comparisonEndExclusive()).isEqualTo(Instant.parse(previousEnd));
        assertThat(view.months()).hasSize(months);
        assertThat(view.months())
                .allSatisfy(
                        p -> {
                            assertThat(p.goodsSales()).isZero();
                            assertThat(p.previousYearGoodsSales()).isZero();
                        });
        verify(readModel).monthly(org, Instant.parse(start), Instant.parse(now));
        verify(readModel).monthly(org, Instant.parse(previousStart), Instant.parse(previousEnd));
    }

    @Test
    void noCurrentActivityAgainstPriorSalesIsMinusOneHundredPercent() {
        when(readModel.monthly(eq(org), any(), any())).thenReturn(List.of());
        when(readModel.monthly(
                        org,
                        Instant.parse("2025-08-01T00:00:00Z"),
                        Instant.parse("2025-10-21T02:00:00Z")))
                .thenReturn(List.of(new MonthlyTotals("2025-09", 2, new BigDecimal("1500"))));
        var view = service.overview(org, 3);
        assertThat(view.averageBasket()).isNull();
        assertThat(view.goodsSalesChangePercent()).isEqualByComparingTo("-100.0");
        assertThat(view.confirmedOrdersChangePercent()).isEqualByComparingTo("-100.0");
    }

    @Test
    void newSalesWithNoPriorActivityDoNotInventPercentageGrowth() {
        when(readModel.monthly(eq(org), any(), any())).thenReturn(List.of());
        when(readModel.monthly(org, Instant.parse("2026-08-01T00:00:00Z"), clock.instant()))
                .thenReturn(List.of(new MonthlyTotals("2026-10", 2, new BigDecimal("2001"))));
        var view = service.overview(org, 3);
        assertThat(view.averageBasket()).isEqualByComparingTo("1001");
        assertThat(view.goodsSalesChangePercent()).isNull();
        assertThat(view.confirmedOrdersChangePercent()).isNull();
    }
}
