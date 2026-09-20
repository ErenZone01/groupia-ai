package sn.groupia.shared.infrastructure;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
@Configuration(proxyBeanMethods=false) @EnableAsync @EnableScheduling
class EventProcessingConfiguration {}
