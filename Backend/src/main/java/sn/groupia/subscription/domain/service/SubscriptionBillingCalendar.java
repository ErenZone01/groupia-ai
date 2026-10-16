package sn.groupia.subscription.domain.service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public final class SubscriptionBillingCalendar {
    private SubscriptionBillingCalendar() {}

    public record Period(Instant start, Instant end) {}

    public static Period next(Instant now, Instant coverageEnd, String timezone) {
        Instant start =
                (coverageEnd != null && coverageEnd.isAfter(now) ? coverageEnd : now)
                        .truncatedTo(ChronoUnit.MICROS);
        return new Period(
                start,
                start.atZone(ZoneId.of(timezone))
                        .plusMonths(1)
                        .toInstant()
                        .truncatedTo(ChronoUnit.MICROS));
    }

    public static String key(UUID subscription, Instant start) {
        return "STANDARD_MONTHLY:"
                + subscription
                + ":"
                + Math.addExact(
                        Math.multiplyExact(start.getEpochSecond(), 1000000L),
                        start.getNano() / 1000);
    }
}
