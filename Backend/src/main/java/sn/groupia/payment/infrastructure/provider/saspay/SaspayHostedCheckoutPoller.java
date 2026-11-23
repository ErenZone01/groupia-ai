package sn.groupia.payment.infrastructure.provider.saspay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sn.groupia.payment.application.service.PaymentService;

/**
 * Read-only financial reconciliation. A checkout success redirect can be lost, and SasPay webhooks
 * contain transaction IDs rather than session IDs. Recover both hosted and legacy attempts using
 * durable scheduling. Never initiates, resubmits or cancels a payment.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "groupia.payment.saspay.enabled", havingValue = "true")
public class SaspayHostedCheckoutPoller {
    private static final Logger log = LoggerFactory.getLogger(SaspayHostedCheckoutPoller.class);
    private final SaspayPaymentRecoveryQueue queue;
    private final String account;
    private final PaymentService paymentService;

    SaspayHostedCheckoutPoller(
            SaspayPaymentRecoveryQueue queue,
            PaymentService paymentService,
            SaspayProperties properties) {
        this.queue = queue;
        this.account = properties.merchantId().toString();
        this.paymentService = paymentService;
    }

    @Scheduled(
            fixedDelayString = "${groupia.payment.saspay.hosted-poll-delay-ms:60000}",
            initialDelayString = "${groupia.payment.saspay.hosted-poll-initial-delay-ms:60000}")
    public void poll() {
        // Durable leases coordinate replicas; financial locks still protect allocation.
        for (var work : queue.claim(account)) {
            try {
                // GET session status/detail and GET transaction verify only.
                paymentService.reconcile(work.paymentId());
            } catch (RuntimeException ex) {
                log.warn("SasPay reconciliation deferred for attempt {}", work.paymentId());
            } finally {
                queue.complete(work);
            }
        }
    }
}
