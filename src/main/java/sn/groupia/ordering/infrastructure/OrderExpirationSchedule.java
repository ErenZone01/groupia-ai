package sn.groupia.ordering.infrastructure;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sn.groupia.ordering.application.service.OrderExpiration;
@Component @ConditionalOnProperty(name="groupia.ordering.expiration.enabled",havingValue="true",matchIfMissing=true)
class OrderExpirationSchedule {
    private final OrderExpiration expiration;
    OrderExpirationSchedule(OrderExpiration expiration) { this.expiration=expiration; }
    @Scheduled(fixedDelayString="${groupia.ordering.expiration.delay:60000}",initialDelayString="${groupia.ordering.expiration.delay:60000}")
    void expire() { expiration.expireDue(); }
}
