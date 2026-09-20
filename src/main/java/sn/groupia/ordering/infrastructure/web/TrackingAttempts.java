package sn.groupia.ordering.infrastructure.web;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;
import sn.groupia.shared.api.ApiException;

/** Bounded per-process protection; use the trusted remote address, never client Forwarded headers. */
@Component
class TrackingAttempts {
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();
    TrackingAttempts(Clock clock) { this.clock = clock; }
    synchronized void check(String address) {
        long now = clock.millis();
        windows.values().removeIf(w -> now >= w.until);
        var window = windows.get(address);
        if (window == null) {
            if (windows.size() >= 10000) { throw limited(); }
            window = new Window(now + 60000); windows.put(address, window);
        }
        if (++window.count > 60) { throw limited(); }
    }
    private ApiException limited() { return new ApiException(429, "ORDER_TRACKING_RATE_LIMITED", "Trop de tentatives de suivi. Réessayez plus tard."); }
    private static class Window {
        private final long until;
        private int count;
        Window(long until) { this.until = until; }
    }
}
