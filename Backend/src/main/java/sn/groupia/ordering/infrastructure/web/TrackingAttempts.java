package sn.groupia.ordering.infrastructure.web;

import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;
import sn.groupia.shared.api.ApiException;

/**
 * Bounded per-process protection; use the trusted remote address, never client Forwarded headers.
 */
@Component
class TrackingAttempts {
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();
    private final int capacity, limit;

    TrackingAttempts(
            Clock clock,
            @org.springframework.beans.factory.annotation.Value(
                            "${groupia.ordering.tracking-rate-limit.capacity:10000}")
                    int capacity,
            @org.springframework.beans.factory.annotation.Value(
                            "${groupia.ordering.tracking-rate-limit.per-minute:60}")
                    int limit) {
        if (capacity < 1 || capacity > 100000 || limit < 1)
            throw new IllegalArgumentException("Invalid tracking limit");
        this.clock = clock;
        this.capacity = capacity;
        this.limit = limit;
    }

    synchronized void check(String address) {
        long now = clock.millis();
        windows.values().removeIf(w -> now >= w.until);
        var window = windows.get(address);
        if (window == null) {
            if (windows.size() >= capacity) {
                throw limited();
            }
            window = new Window(now + 60000);
            windows.put(address, window);
        }
        if (++window.count > limit) {
            throw limited();
        }
    }

    private ApiException limited() {
        return new ApiException(
                429,
                "ORDER_TRACKING_RATE_LIMITED",
                "Trop de tentatives de suivi. Réessayez plus tard.");
    }

    private static class Window {
        private final long until;
        private int count;

        Window(long until) {
            this.until = until;
        }
    }
}
