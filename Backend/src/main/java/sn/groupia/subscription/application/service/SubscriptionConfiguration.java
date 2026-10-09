package sn.groupia.subscription.application.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sn.groupia.subscription.infrastructure.provider.UnavailableSubscriptionBillingProvider;

@Configuration
class SubscriptionConfiguration {
    @Bean
    @ConditionalOnMissingBean(SubscriptionBillingProvider.class)
    SubscriptionBillingProvider subscriptionBillingProvider() {
        return new UnavailableSubscriptionBillingProvider();
    }
}
