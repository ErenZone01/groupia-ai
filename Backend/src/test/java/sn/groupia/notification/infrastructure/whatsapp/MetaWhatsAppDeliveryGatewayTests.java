package sn.groupia.notification.infrastructure.whatsapp;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import sn.groupia.notification.api.*;
import sn.groupia.notification.api.NotificationContentSource.LinkContent;
import static sn.groupia.notification.infrastructure.whatsapp.WhatsAppProperties.Source.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@ExtendWith(OutputCaptureExtension.class)
class MetaWhatsAppDeliveryGatewayTests {
    private static final String PHONE = "+221771234567", CODE = "183729";
    private static final String LINK = "https://groupia.test/s/private-test-link";
    private static final String TOKEN = "test-only-meta-secret";
    private final UUID id = UUID.randomUUID();
    private final LinkContent content = new LinkContent(PHONE, "GRP-000000000008", LINK);
    private final JsonMapper json = JsonMapper.builder().build();
    private MockRestServiceServer server;
    private MetaWhatsAppDeliveryGateway gateway;

    static WhatsAppProperties properties() {
        var confirmed = new WhatsAppProperties.UtilityTemplate("confirmation_fixture", "fr", List.of(ORDER_NUMBER),
                List.of(), 0, "https://groupia.test/s/");
        var tracking = new WhatsAppProperties.UtilityTemplate("tracking_fixture", "fr", List.of(),
                List.of(MAGIC_URL, ORDER_NUMBER), null, null);
        return new WhatsAppProperties(true, true, true, "v23.0", "123456789", TOKEN, Duration.ofSeconds(1), Duration.ofSeconds(1),
                new WhatsAppProperties.Templates(confirmed, tracking, new WhatsAppProperties.OtpTemplate("otp_fixture", "fr", WhatsAppProperties.OtpMode.AUTHENTICATION, List.of(), 5), Map.of()));
    }
    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl("https://graph.facebook.com");
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new MetaWhatsAppDeliveryGateway(properties(), builder.build(), json);
    }
    private org.springframework.test.web.client.ResponseActions request() {
        return server.expect(requestTo("https://graph.facebook.com/v23.0/123456789/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.messaging_product").value("whatsapp"))
                .andExpect(jsonPath("$.recipient_type").value("individual"))
                .andExpect(jsonPath("$.to").value("221771234567"))
                .andExpect(jsonPath("$.type").value("template"))
                .andExpect(jsonPath("$.template.language.code").value("fr"));
    }
    private void useProperties(WhatsAppProperties properties) {
        var builder = RestClient.builder().baseUrl("https://graph.facebook.com");
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new MetaWhatsAppDeliveryGateway(properties, builder.build(), json);
    }
    private WhatsAppProperties otpOnlyUtility() {
        var p = properties();
        return new WhatsAppProperties(true, true, false, p.graphApiVersion(), p.phoneNumberId(), p.accessToken(),
                p.connectTimeout(), p.readTimeout(), new WhatsAppProperties.Templates(null, null,
                new WhatsAppProperties.OtpTemplate("utility_otp_fixture", "fr", WhatsAppProperties.OtpMode.UTILITY,
                        List.of(WhatsAppProperties.OtpSource.OTP_CODE), null), Map.of()));
    }
    @Test void utilitySendsOnlyConfiguredBodyWithExistingOtpAndNoButton(CapturedOutput output) {
        var p = otpOnlyUtility();
        p.validate(301, false); // Utility has no provider expiry requirement; customeraccess retains its TTL.
        useProperties(p);
        request().andExpect(jsonPath("$.template.name").value("utility_otp_fixture"))
                .andExpect(jsonPath("$.template.components.length()").value(1))
                .andExpect(jsonPath("$.template.components[0].type").value("body"))
                .andExpect(jsonPath("$.template.components[0].parameters.length()").value(1))
                .andExpect(jsonPath("$.template.components[0].parameters[0].type").value("text"))
                .andExpect(jsonPath("$.template.components[0].parameters[0].text").value(CODE))
                .andRespond(withSuccess("{\"messages\":[{\"id\":\"wamid.utility\"}]}", MediaType.APPLICATION_JSON));
        assertThat(gateway.sendOtp(id, PHONE, CODE).providerMessageId()).isEqualTo("wamid.utility");
        server.verify(); assertNoSecrets(output);
    }
    @Test void utilityFailureIsAsRedactedAsAuthentication(CapturedOutput output) {
        useProperties(otpOnlyUtility());
        request().andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .body(json.writeValueAsString(Map.of("error", Map.of("code", 132000, "message", TOKEN + CODE + PHONE)))));
        assertSafeFailure(() -> gateway.sendOtp(id, PHONE, CODE));
        assertNoSecrets(output); server.verify();
    }
    @Test void disabledLinksSkipEveryNotificationWithoutTemplatesHttpOrWarnings(CapturedOutput output) {
        useProperties(otpOnlyUtility());
        for (var type : NotificationType.values()) {
            assertThat(gateway.sendLink(id, type, content)).isEqualTo(new DeliveryResult("DISABLED", null));
        }
        server.verify(); assertNoSecrets(output);
        assertThat(output.getAll()).doesNotContain("WHATSAPP_DELIVERY_FAILED");
    }
    @Test void disabledOtpNeverReadsMissingTemplateOrCallsMeta(CapturedOutput output) {
        var p = properties();
        useProperties(new WhatsAppProperties(true, false, false, p.graphApiVersion(), p.phoneNumberId(), p.accessToken(),
                p.connectTimeout(), p.readTimeout(), null));
        assertThatThrownBy(() -> gateway.sendOtp(id, PHONE, CODE)).hasMessage("WHATSAPP_OTP_DISABLED").hasNoCause();
        server.verify(); assertNoSecrets(output);
    }
    @Test void confirmationUsesConfiguredHeaderAndDynamicUrl() {
        request().andExpect(jsonPath("$.template.name").value("confirmation_fixture"))
                .andExpect(jsonPath("$.template.components.length()").value(2))
                .andExpect(jsonPath("$.template.components[0].type").value("header"))
                .andExpect(jsonPath("$.template.components[0].parameters[0].text").value(content.orderNumber()))
                .andExpect(jsonPath("$.template.components[1].type").value("button"))
                .andExpect(jsonPath("$.template.components[1].sub_type").value("url"))
                .andExpect(jsonPath("$.template.components[1].index").value("0"))
                .andExpect(jsonPath("$.template.components[1].parameters[0].text").value("private-test-link"))
                .andRespond(withSuccess("{\"messages\":[{\"id\":\"wamid.test\"}]}", MediaType.APPLICATION_JSON));
        var result = gateway.sendLink(id, NotificationType.ORDER_CONFIRMED, content);
        assertThat(result).isEqualTo(new DeliveryResult("META_WHATSAPP", "wamid.test"));
        assertThat(result.toString()).doesNotContain("wamid.test");
        server.verify();
    }
    @Test void resendUsesItsOwnTemplateAndOrderedBodyMapping() {
        request().andExpect(jsonPath("$.template.name").value("tracking_fixture"))
                .andExpect(jsonPath("$.template.components.length()").value(1))
                .andExpect(jsonPath("$.template.components[0].type").value("body"))
                .andExpect(jsonPath("$.template.components[0].parameters[0].text").value(LINK))
                .andExpect(jsonPath("$.template.components[0].parameters[1].text").value(content.orderNumber()))
                .andRespond(withSuccess("{\"messages\":[{\"id\":\"wamid.test\"}]}", MediaType.APPLICATION_JSON));
        gateway.sendLink(id, NotificationType.ORDER_TRACKING_LINK, content); server.verify();
    }
    @Test void authenticationCopyCodeUsesExactlyTheChallengeInBodyAndButton() {
        request().andExpect(jsonPath("$.template.name").value("otp_fixture"))
                .andExpect(jsonPath("$.template.components.length()").value(2))
                .andExpect(jsonPath("$.template.components[0].type").value("body"))
                .andExpect(jsonPath("$.template.components[0].parameters[0].text").value(CODE))
                .andExpect(jsonPath("$.template.components[1].type").value("button"))
                .andExpect(jsonPath("$.template.components[1].sub_type").value("url"))
                .andExpect(jsonPath("$.template.components[1].index").value("0"))
                .andExpect(jsonPath("$.template.components[1].parameters[0].text").value(CODE))
                .andRespond(withSuccess("{\"messages\":[{\"id\":\"wamid.otp\"}]}", MediaType.APPLICATION_JSON));
        assertThat(gateway.sendOtp(id, PHONE, CODE).providerMessageId()).isEqualTo("wamid.otp");
        server.verify();
    }
    @ParameterizedTest @ValueSource(ints = {400, 401, 429, 500})
    void metaErrorsExposeOnlyNumericDiagnostics(int status, CapturedOutput output) {
        request().andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(Map.of("error", Map.of("code", 131000, "error_subcode", 42,
                        "message", TOKEN + PHONE + CODE + LINK, "fbtrace_id", TOKEN)))));
        assertSafeFailure(() -> gateway.sendOtp(id, PHONE, CODE));
        assertThat(output.getAll()).contains("httpStatus=" + status, "metaCode=131000", "metaSubcode=42", id.toString());
        assertNoSecrets(output); server.verify();
    }
    @Test void malformedErrorBodyIsNotRetained(CapturedOutput output) {
        request().andRespond(withStatus(HttpStatus.BAD_REQUEST).body(TOKEN + PHONE + LINK));
        assertSafeFailure(() -> gateway.sendLink(id, NotificationType.ORDER_CONFIRMED, content));
        assertNoSecrets(output); server.verify();
    }
    @Test void stringErrorCodesAreNotReflected(CapturedOutput output) {
        request().andRespond(withStatus(HttpStatus.BAD_REQUEST).body(json.writeValueAsString(
                Map.of("error", Map.of("code", TOKEN, "error_subcode", LINK)))));
        assertSafeFailure(() -> gateway.sendOtp(id, PHONE, CODE));
        assertThat(output.getAll()).contains("metaCode=null", "metaSubcode=null");
        assertNoSecrets(output);
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "null", "{\"messages\":[{}]}", "{\"messages\":[{\"id\":17}]}", "not-json"})
    void malformedAcceptanceDoesNotCompleteThePublication(String body, CapturedOutput output) {
        request().andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertSafeFailure(() -> gateway.sendOtp(id, PHONE, CODE)); assertNoSecrets(output);
    }
    @Test void oversizedResponseIsRejected(CapturedOutput output) {
        request().andRespond(withSuccess("x".repeat(65537), MediaType.APPLICATION_JSON));
        assertSafeFailure(() -> gateway.sendOtp(id, PHONE, CODE)); assertNoSecrets(output);
    }
    @Test void unconfiguredLogisticsNeverFallbackToConfirmation() {
        assertSafeFailure(() -> gateway.sendLink(id, NotificationType.ARRIVED, content)); server.verify();
    }
    @Test void explicitAdditionalTemplateIsSupportedWithoutAdapterChanges() {
        var p = properties();
        var configured = new WhatsAppProperties(p.enabled(), p.otpEnabled(), p.linksEnabled(), p.graphApiVersion(), p.phoneNumberId(), p.accessToken(),
                p.connectTimeout(), p.readTimeout(), new WhatsAppProperties.Templates(p.templates().orderConfirmed(),
                p.templates().orderTrackingLink(), p.templates().otp(), Map.of(NotificationType.ARRIVED, p.templates().orderTrackingLink())));
        var builder = RestClient.builder().baseUrl("https://graph.facebook.com");
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new MetaWhatsAppDeliveryGateway(configured, builder.build(), json);
        request().andExpect(jsonPath("$.template.name").value("tracking_fixture"))
                .andRespond(withSuccess("{\"messages\":[{\"id\":\"wamid.test\"}]}", MediaType.APPLICATION_JSON));
        gateway.sendLink(id, NotificationType.ARRIVED, content); server.verify();
    }
    @ParameterizedTest @ValueSource(strings = {"771234567", "+221 77 123 45 67", "invalid"})
    void adapterDoesNotInventANewPhoneNormalization(String phone) {
        assertSafeFailure(() -> gateway.sendOtp(id, phone, CODE)); server.verify();
    }
    @Test void mismatchedApprovedUrlPrefixNeverSends() {
        assertSafeFailure(() -> gateway.sendLink(id, NotificationType.ORDER_CONFIRMED,
                new LinkContent(PHONE, "GRP-1", "https://other.test/s/token")));
        server.verify();
    }
    @Test void shortCanonicalE164IsOnlyAdaptedNotRenormalized() {
        server.expect(requestTo("https://graph.facebook.com/v23.0/123456789/messages"))
                .andExpect(jsonPath("$.to").value("6903010"))
                .andRespond(withSuccess("{\"messages\":[{\"id\":\"wamid.short\"}]}", MediaType.APPLICATION_JSON));
        gateway.sendOtp(id, "+6903010", CODE);
        server.verify();
    }
    @Test void invalidOtpNeverSends() {
        assertSafeFailure(() -> gateway.sendOtp(id, PHONE, "12345")); server.verify();
    }
    @Test void timeoutIsSafeAndNotRetried(CapturedOutput output) throws Exception {
        var calls = new AtomicInteger();
        var fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        fake.start();
        try {
            var p = properties();
            var shortTimeout = new WhatsAppProperties(true, true, true, p.graphApiVersion(), p.phoneNumberId(), p.accessToken(),
                    Duration.ofSeconds(1), Duration.ofMillis(150), p.templates());
            var http = MetaWhatsAppDeliveryGateway.http(shortTimeout).mutate()
                    .baseUrl("http://127.0.0.1:" + fake.getAddress().getPort()).build();
            var realTransport = new MetaWhatsAppDeliveryGateway(shortTimeout, http, json);
            assertSafeFailure(() -> realTransport.sendOtp(id, PHONE, CODE));
            assertThat(calls).hasValue(1); assertNoSecrets(output);
        } finally { fake.stop(0); }
    }
    @Test void redirectsAreNeverFollowed(CapturedOutput output) throws Exception {
        var calls = new AtomicInteger();
        var fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "/would-leak-credentials");
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(307, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        fake.start();
        try {
            var http = MetaWhatsAppDeliveryGateway.http(properties()).mutate()
                    .baseUrl("http://127.0.0.1:" + fake.getAddress().getPort()).build();
            var realTransport = new MetaWhatsAppDeliveryGateway(properties(), http, json);
            assertSafeFailure(() -> realTransport.sendOtp(id, PHONE, CODE));
            assertThat(calls).hasValue(1); assertNoSecrets(output);
        } finally { fake.stop(0); }
    }
    private void assertSafeFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(WhatsAppDeliveryException.class)
                .hasMessage("WHATSAPP_DELIVERY_FAILED").hasNoCause();
    }
    private void assertNoSecrets(CapturedOutput output) {
        assertThat(output.getAll()).doesNotContain(TOKEN, PHONE, PHONE.substring(1), CODE, LINK, "private-test-link", "Authorization");
    }
}
