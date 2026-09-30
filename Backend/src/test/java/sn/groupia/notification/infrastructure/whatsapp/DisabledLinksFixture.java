package sn.groupia.notification.infrastructure.whatsapp;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.client.RestClient;
import sn.groupia.notification.api.DeliveryGateway;
import tools.jackson.databind.json.JsonMapper;
/** Test-only real adapter with all HTTP calls forbidden; no external credentials. */
public final class DisabledLinksFixture {
    public final AtomicInteger httpCalls = new AtomicInteger();
    public final DeliveryGateway gateway;
    public DisabledLinksFixture() {
        var properties = new WhatsAppProperties(true, true, false, "v23.0", "123456789", "test-only",
            Duration.ofSeconds(1), Duration.ofSeconds(1), new WhatsAppProperties.Templates(null, null,
            new WhatsAppProperties.OtpTemplate("utility_fixture", "fr", WhatsAppProperties.OtpMode.UTILITY,
                List.of(WhatsAppProperties.OtpSource.OTP_CODE), null), Map.of()));
        properties.validate(300, false);
        var http = RestClient.builder().requestFactory((uri, method) -> {
            httpCalls.incrementAndGet();
            throw new AssertionError("HTTP_FORBIDDEN_FOR_DISABLED_LINKS");
        }).build();
        gateway = new MetaWhatsAppDeliveryGateway(properties, http, JsonMapper.builder().build());
    }
}
