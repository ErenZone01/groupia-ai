package sn.groupia.notification.infrastructure.whatsapp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import sn.groupia.notification.api.DeliveryGateway;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "groupia.notification.whatsapp.enabled", havingValue = "true")
@EnableConfigurationProperties(WhatsAppProperties.class)
public class WhatsAppConfiguration {
    @Bean
    DeliveryGateway metaWhatsAppDeliveryGateway(WhatsAppProperties properties, Environment environment, ObjectMapper json) {
        properties.validate(environment.getProperty("groupia.customer-access.otp-seconds", Integer.class, 300),
                environment.acceptsProfiles(Profiles.of("customer-access-local")));
        return new MetaWhatsAppDeliveryGateway(properties, MetaWhatsAppDeliveryGateway.http(properties), json);
    }
}
