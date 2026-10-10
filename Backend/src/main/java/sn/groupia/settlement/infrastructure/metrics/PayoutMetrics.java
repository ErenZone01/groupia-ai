package sn.groupia.settlement.infrastructure.metrics;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Counts only module-owned records; no customer or organization metric labels. */
@Component
class PayoutMetrics implements MeterBinder {
    private final JdbcTemplate jdbc;

    PayoutMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(
                        "groupia.payouts.review",
                        jdbc,
                        db ->
                                db.queryForObject(
                                        "select count(*) from merchant_payouts where reconciliation_required",
                                        Long.class))
                .register(registry);
        Gauge.builder(
                        "groupia.payouts.pending",
                        jdbc,
                        db ->
                                db.queryForObject(
                                        "select count(*) from merchant_payouts where status in ('CREATED','PENDING')",
                                        Long.class))
                .register(registry);
    }
}
