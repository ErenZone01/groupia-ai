package sn.groupia.subscription.domain.service;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SubscriptionBillingCalendarTests {
    @ParameterizedTest
    @CsvSource({
        "2027-01-31T12:00:00Z,Africa/Dakar,2027-02-28T12:00:00Z",
        "2028-01-31T12:00:00Z,Africa/Dakar,2028-02-29T12:00:00Z",
        "2027-02-28T12:00:00Z,Africa/Dakar,2027-03-28T12:00:00Z",
        "2026-02-28T23:00:00Z,Africa/Dakar,2026-03-28T23:00:00Z",
        "2026-02-28T23:00:00Z,Europe/Paris,2026-03-31T22:00:00Z",
        "2026-02-28T01:30:00Z,Europe/Paris,2026-03-28T01:30:00Z",
        "2026-09-25T00:30:00Z,Europe/Paris,2026-10-25T00:30:00Z",
        "2026-03-01T00:00:00.123456789Z,Africa/Dakar,2026-04-01T00:00:00.123456Z"
    })
    void calendarMonth(String from, String zone, String end) {
        assertThat(SubscriptionBillingCalendar.next(Instant.parse(from), null, zone).end())
                .isEqualTo(Instant.parse(end));
    }

    @Test
    void gapMovesLocalTimeForward() {
        // 29 February in a leap year -> 29 March gap in Europe/Paris (2020).
        var p =
                SubscriptionBillingCalendar.next(
                        Instant.parse("2020-02-29T01:30:00Z"), null, "Europe/Paris");
        assertThat(p.end()).isEqualTo(Instant.parse("2020-03-29T01:30:00Z"));
        assertThat(p.end().atZone(ZoneId.of("Europe/Paris")).getHour()).isEqualTo(3);
    }

    @Test
    void trialAndRenewalUseCoverageButLongLapseStartsNow() {
        var now = Instant.parse("2026-11-10T00:00:00Z");
        var coverage = Instant.parse("2026-11-20T00:00:00Z");
        assertThat(SubscriptionBillingCalendar.next(now, coverage, "Africa/Dakar").start())
                .isEqualTo(coverage);
        assertThat(
                        SubscriptionBillingCalendar.next(
                                        coverage.plusSeconds(100), coverage, "Africa/Dakar")
                                .start())
                .isEqualTo(coverage.plusSeconds(100));
    }

    @Test
    void keyIsStableAtDatabasePrecisionAndNotMonthCivil() {
        var id = UUID.randomUUID();
        var p =
                SubscriptionBillingCalendar.next(
                        Instant.parse("2026-11-20T00:00:00.123456789Z"), null, "Africa/Dakar");
        assertThat(SubscriptionBillingCalendar.key(id, p.start()))
                .isEqualTo("STANDARD_MONTHLY:" + id + ":1795132800123456");
    }
}
