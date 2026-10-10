package sn.groupia.payment.infrastructure.metrics;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Counts only module-owned records; no customer or organization metric labels. */
@Component
class PaymentMetrics implements MeterBinder {
    private final JdbcTemplate jdbc;

    PaymentMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(
                        "groupia.payments.review",
                        jdbc,
                        db ->
                                db.queryForObject(
                                        "select count(*) from payments where reconciliation_required",
                                        Long.class))
                .register(registry);
        Gauge.builder(
                        "groupia.webhooks.review",
                        jdbc,
                        db ->
                                db.queryForObject(
                                        "select count(*) from payment_webhook_events where processing_status='REVIEW_REQUIRED'",
                                        Long.class))
                .register(registry);
        Gauge.builder(
                        "groupia.webhooks.exhausted",
                        jdbc,
                        db ->
                                db.queryForObject(
                                        "select count(*) from payment_webhook_events where processing_status='REVIEW_REQUIRED' and attempt_count>=5",
                                        Long.class))
                .register(registry);
    }
}
