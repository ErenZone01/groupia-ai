package sn.groupia.payment.infrastructure.provider.saspay;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import sn.groupia.payment.domain.repository.PaymentQuoteStore;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SaspayProperties.class)
class SaspayConfiguration {
    @Bean
    @ConditionalOnProperty(name = "groupia.payment.saspay.enabled", havingValue = "true")
    SaspayPaymentProvider saspayPaymentProvider(
            SaspayProperties p,
            PaymentQuoteStore quotes,
            ObjectMapper json,
            Clock clock,
            Environment environment,
            @org.springframework.beans.factory.annotation.Value(
                            "${groupia.payment.saspay.allow-fractional-xof-fees:false}")
                    boolean allowFractionalXofFees) {
        validateProfile(p, environment);
        return new SaspayPaymentProvider(
                new SaspayClient(SaspayClient.builder(p).build(), json, clock),
                p,
                quotes,
                json,
                clock,
                allowFractionalXofFees);
    }

    static void validateProfile(SaspayProperties p, Environment environment) {
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        if (p.isLive() && !prod)
            throw new IllegalArgumentException("SasPay live pilot requires Spring prod profile");
        if (!p.isLive() && prod)
            throw new IllegalArgumentException(
                    "SasPay prod profile requires an explicitly enabled live pilot");
    }
}
