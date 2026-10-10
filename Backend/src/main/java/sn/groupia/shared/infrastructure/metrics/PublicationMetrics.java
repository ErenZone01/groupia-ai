package sn.groupia.shared.infrastructure.metrics;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Counts only module-owned records; no customer or organization metric labels. */
@Component
class PublicationMetrics implements MeterBinder {
    private final JdbcTemplate jdbc;

    PublicationMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(
                        "groupia.events.failed",
                        jdbc,
                        db ->
                                db.queryForObject(
                                        "select count(*) from event_publication where status='FAILED'",
                                        Long.class))
                .register(registry);
        Gauge.builder(
                        "groupia.events.exhausted",
                        jdbc,
                        db ->
                                db.queryForObject(
                                        "select count(*) from event_publication where status='FAILED' and completion_attempts>=5",
                                        Long.class))
                .register(registry);
    }
}
