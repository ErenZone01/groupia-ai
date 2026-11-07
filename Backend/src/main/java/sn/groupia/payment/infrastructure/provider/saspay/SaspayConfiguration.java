package sn.groupia.payment.infrastructure.provider.saspay;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import sn.groupia.payment.domain.repository.PaymentQuoteStore;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SaspayProperties.class)
class SaspayConfiguration {
    @Bean
    @ConditionalOnProperty(name = "groupia.payment.saspay.enabled", havingValue = "true")
    SaspayPaymentProvider saspayPaymentProvider(
            SaspayProperties p, PaymentQuoteStore quotes, ObjectMapper json, Clock clock) {
        return new SaspayPaymentProvider(
                new SaspayClient(SaspayClient.builder(p).build(), json, clock),
                p,
                quotes,
                json,
                clock);
    }
}
