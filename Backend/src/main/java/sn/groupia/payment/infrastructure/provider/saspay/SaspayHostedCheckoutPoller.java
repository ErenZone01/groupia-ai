package sn.groupia.payment.infrastructure.provider.saspay;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sn.groupia.payment.application.service.PaymentService;

/**
 * Read-only financial reconciliation. A checkout success redirect can be lost, and SasPay
 * webhooks contain transaction IDs rather than session IDs. Polling the durable session
 * ID is the supported recovery path. Never initiates, retries or cancels a payment.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "groupia.payment.saspay.enabled", havingValue = "true")
public class SaspayHostedCheckoutPoller {
    private static final Logger log = LoggerFactory.getLogger(SaspayHostedCheckoutPoller.class);
    private final JdbcTemplate jdbc;
    private final PaymentService paymentService;

    SaspayHostedCheckoutPoller(JdbcTemplate jdbc, PaymentService paymentService) {
        this.jdbc = jdbc;
        this.paymentService = paymentService;
    }

    @Scheduled(fixedDelayString = "${groupia.payment.saspay.hosted-poll-delay-ms:60000}",
            initialDelayString = "${groupia.payment.saspay.hosted-poll-initial-delay-ms:60000}")
    public void poll() {
        // One bounded batch per run. The server may have multiple replicas; optimistic
        // locks and allocation constraints prevent double financial settlement.
        var ids = jdbc.query(
                "select id from payments"
                        + " where provider='SASPAY'"
                        + " and ((provider_charge_id is not null and status='PENDING')"
                        + "   or (provider_charge_id is null and status='CREATED'"
                        + "       and fee_quote_id is null and reconciliation_required=true))"
                        + " and (last_checked_at is null"
                        + "   or last_checked_at < now() - interval '50 seconds')"
                        + " order by initiated_at, id limit 25",
                (rs, index) -> rs.getObject(1, UUID.class));
        for (var id : ids) {
            try {
                // GET session status/detail and GET transaction verify only.
                paymentService.reconcile(id);
            } catch (RuntimeException ex) {
                log.warn("SasPay hosted reconciliation deferred for attempt {}", id);
            }
        }
    }
}
