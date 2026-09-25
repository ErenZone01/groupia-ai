package sn.groupia.payment.infrastructure;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sn.groupia.payment.application.service.PaymentWebhooks;
@Component @ConditionalOnProperty(name="groupia.payment.webhook-retry.enabled",havingValue="true",matchIfMissing=true)
class PaymentWebhookSchedule {
    private final PaymentWebhooks webhooks;
    PaymentWebhookSchedule(PaymentWebhooks webhooks) { this.webhooks=webhooks; }
    @Scheduled(fixedDelayString="${groupia.payment.webhook-retry.delay:60000}",initialDelayString="${groupia.payment.webhook-retry.delay:60000}")
    void retry() { webhooks.retryPending(); }
}
