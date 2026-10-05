package sn.groupia.payment.infrastructure.provider.bictorys;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties({BictorysProperties.class,BictorysPayoutProperties.class})
class BictorysConfiguration {
    @Bean
    BictorysRefundProvider bictorysRefundProvider(BictorysProperties base,BictorysPayoutProperties payout){
        if(payout.enabled()&&!base.enabled())throw new IllegalArgumentException("Enable the sandbox payment provider before payouts");
        return new BictorysRefundProvider(payout,base.webhookSecret(),BictorysRefundProvider.client(base,payout));
    }
    @Bean @ConditionalOnProperty(name="groupia.payment.bictorys.enabled",havingValue="true")
    BictorysPaymentProvider bictorysPaymentProvider(BictorysProperties properties) {
        return new BictorysPaymentProvider(new BictorysClient(BictorysClient.builder(properties).build()),properties);
    }
}
