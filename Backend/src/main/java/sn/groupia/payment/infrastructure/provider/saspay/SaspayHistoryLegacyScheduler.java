package sn.groupia.payment.infrastructure.provider.saspay;

import java.time.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keep the PR #100 log-only schedule when durable monitoring has not been opted into. */
@Component
@ConditionalOnProperty(
        name = {"groupia.payment.saspay.enabled", "groupia.payment.saspay.history-audit.enabled"},
        havingValue = "true")
@ConditionalOnProperty(
        name = "groupia.payment.saspay.incident-monitor.enabled",
        havingValue = "false",
        matchIfMissing = true)
final class SaspayHistoryLegacyScheduler {
    private static final Logger log = LoggerFactory.getLogger(SaspayHistoryLegacyScheduler.class);
    private final SaspayHistoryAudit audit;
    private final Clock clock;

    SaspayHistoryLegacyScheduler(SaspayHistoryAudit audit, Clock clock) {
        this.audit = audit;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${groupia.payment.saspay.history-audit.interval-ms:900000}",
            initialDelayString = "${groupia.payment.saspay.history-audit.initial-delay-ms:60000}")
    void scheduled() {
        Instant until = clock.instant();
        try {
            var result = audit.compare(until.minus(Duration.ofHours(48)), until);
            if (result.unmatched() > 0 || result.differences() > 0)
                log.warn(
                        "SasPay history audit pages={} transactions={} linked={} unmatched={} differences={}",
                        result.pages(),
                        result.transactions(),
                        result.linked(),
                        result.unmatched(),
                        result.differences());
            else
                log.info(
                        "SasPay history audit pages={} transactions={} linked={} unmatched=0 differences=0",
                        result.pages(),
                        result.transactions(),
                        result.linked());
        } catch (RuntimeException failure) {
            log.error("SasPay history audit incomplete; no financial data modified");
        }
    }
}
