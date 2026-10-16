package sn.groupia;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sn.groupia.customeraccess.application.service.*;
import sn.groupia.notification.api.*;
import sn.groupia.ordering.api.event.OrderCommerciallyAccepted;
import tools.jackson.databind.*;

@SpringBootTest(
        properties = {
            "groupia.customer-access.enabled=true",
            "groupia.customer-access.public-base-url=https://groupia.test"
        })
@org.junit.jupiter.api.extension.ExtendWith(
        org.springframework.boot.test.system.OutputCaptureExtension.class)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, CustomerAccessIntegrationTests.DeliveryConfig.class})
class CustomerAccessIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired PasswordEncoder passwords;
    @Autowired CustomerAccess access;
    @Autowired AccessCrypto crypto;
    @Autowired FakeDelivery delivery;
    @Autowired org.springframework.context.ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.modulith.events.FailedEventPublications failedPublications;

    static class FakeDelivery implements DeliveryGateway {
        final Map<UUID, String> codes = new ConcurrentHashMap<>();
        final Map<UUID, String> links = new ConcurrentHashMap<>();
        final Map<UUID, NotificationType> types = new ConcurrentHashMap<>();
        volatile boolean fail;
        volatile DeliveryGateway linkDelegate;

        public DeliveryResult sendOtp(UUID id, String phone, String code) {
            if (fail) throw new IllegalStateException("sensitive-adapter-error");
            codes.putIfAbsent(id, code);
            return new DeliveryResult("FAKE", id.toString());
        }

        public DeliveryResult sendLink(
                UUID id, NotificationType type, NotificationContentSource.LinkContent content) {
            if (linkDelegate != null) return linkDelegate.sendLink(id, type, content);
            if (fail) throw new IllegalStateException("sensitive-adapter-error");
            types.putIfAbsent(id, type);
            links.putIfAbsent(id, content.url());
            return new DeliveryResult("FAKE", id.toString());
        }
    }

    @TestConfiguration
    static class DeliveryConfig {
        @Bean
        @Primary
        FakeDelivery fakeDelivery() {
            return new FakeDelivery();
        }
    }

    private UUID actor, org;
    private String jwt, number, trackingToken;
    private JsonNode campaign;
    private static String passwordHash;
    private Cookie csrfCookie;
    private String csrf;
    private static final String BASE = "/api/public/customer-access";

    @BeforeEach
    void setup() throws Exception {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "select count(*) from event_publication where status in ('PUBLISHED','PROCESSING','RESUBMITTED')",
                                                        Long.class))
                                        .isZero());
        TestEventDrain.awaitIdle(jdbc);
        jdbc.execute(
                "TRUNCATE TABLE order_fulfillment_events, shipping_calculations, shipping_measurements, shipping_assessments, customer_access_sessions, otp_challenges, order_access_links, customer_access_limits, shipment_facts, shipments, supplier_order_anomalies, supplier_orders, payment_webhook_events, payments, payment_requests, order_items, orders, customers, grouping_changes, grouping_offers, grouping_campaigns, product_variants, products, organization_members, organizations, user_accounts, event_publication CASCADE");
        delivery.linkDelegate = null;
        delivery.fail = false;
        delivery.codes.clear();
        delivery.links.clear();
        delivery.types.clear();
        if (passwordHash == null) passwordHash = passwords.encode("Customer fixture 2026!");
        actor = UUID.randomUUID();
        jdbc.update(
                "insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at) values (?,'+221771234567',?,'ACTIVE',current_timestamp,current_timestamp)",
                actor,
                passwordHash);
        jwt =
                encoder.encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(MacAlgorithm.HS256).build(),
                                        JwtClaimsSet.builder()
                                                .issuer("https://groupia.test")
                                                .audience(List.of("groupia-api-test"))
                                                .subject(actor.toString())
                                                .issuedAt(Instant.now())
                                                .expiresAt(Instant.now().plusSeconds(1800))
                                                .build()))
                        .getTokenValue();
        org = shop();
        campaign = campaign(org);
        number = order(campaign, "+221771234567");
        var r = mvc.perform(get(BASE + "/csrf")).andExpect(status().isOk()).andReturn();
        csrf = read(r).path("token").asText();
        csrfCookie = r.getResponse().getCookie("__Host-groupia_csrf");
    }

    @Test
    void validMagicIsScopedAndDoesNotReplaceLegacyTracking() throws Exception {
        String link = confirmedLink();
        var r =
                postJson("/magic/exchange", Map.of("token", link), null)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.scope").value("ORDER_ACCESS"))
                        .andReturn();
        Cookie cookie = r.getResponse().getCookie("__Host-groupia_customer");
        assertThat(r.getResponse().getHeader("Set-Cookie"))
                .contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/")
                .doesNotContain("Domain=");
        assertThat(r.getResponse().getContentAsString())
                .doesNotContain(cookie.getValue(), link, "phone");
        assertThat(
                        jdbc.queryForObject(
                                "select token_hash from customer_access_sessions", String.class))
                .isEqualTo(crypto.hash(cookie.getValue()));
        mvc.perform(get(BASE + "/orders/" + number).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        mvc.perform(get(BASE + "/orders").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/orders/GRP-999999999999").cookie(cookie))
                .andExpect(status().isNotFound());
        postJson("/orders/" + number + "/resend-link", Map.of(), cookie)
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/public/orders/track").header("X-Order-Tracking-Token", trackingToken))
                .andExpect(status().isOk());
    }

    @Test
    void forgedMalformedAndUnknownLinksFail() throws Exception {
        String link = confirmedLink();
        for (String value :
                List.of(
                        "bad",
                        link.substring(0, link.length() - 2) + "xx",
                        crypto.link(UUID.randomUUID())))
            postJson("/magic/exchange", Map.of("token", value), null)
                    .andExpect(status().isUnauthorized());
        assertThat(count("customer_access_sessions")).isZero();
    }

    @Test
    void revokedAndExpiredLinksFail() throws Exception {
        String link = confirmedLink();
        Cookie cookie = magic(link);
        jdbc.update("update order_access_links set revoked_at=current_timestamp");
        postJson("/magic/exchange", Map.of("token", link), null)
                .andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/session").cookie(cookie)).andExpect(status().isUnauthorized());
        jdbc.update(
                "update order_access_links set revoked_at=null,created_at=current_timestamp-interval '2 days',expires_at=current_timestamp-interval '1 day'");
        postJson("/magic/exchange", Map.of("token", link), null)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void activeLinksHaveNoShortExpiryButTerminalArchiveIsEnforced() throws Exception {
        String link = confirmedLink();
        assertThat(jdbc.queryForMap("select expires_at from order_access_links").get("expires_at"))
                .isNull();
        jdbc.update(
                "update orders set status='COMPLETED',completed_at=current_timestamp-interval '91 days',placed_at=current_timestamp-interval '92 days',confirmed_at=current_timestamp-interval '92 days' where order_number=?",
                number);
        postJson("/magic/exchange", Map.of("token", link), null)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void normalizedPhoneGroupsHistoricalCustomersAcrossShopsAndPaginates() throws Exception {
        order(campaign, "+221 77 123 45 67");
        UUID second = shop();
        var c = campaign(second);
        String ownOtherShop = order(c, "771234567");
        String foreign = order(c, "+221781234567");
        Cookie cookie = phone("77 123 45 67");
        var result =
                read(
                        mvc.perform(get(BASE + "/orders").param("size", "2").cookie(cookie))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(result.path("totalElements").asLong()).isEqualTo(3);
        assertThat(result.path("items").size()).isEqualTo(2);
        assertThat(result.toString())
                .doesNotContain(
                        "phone",
                        "merchantReference",
                        "providerTransactionId",
                        "PaymentRequest",
                        "trackingReference");
        mvc.perform(get(BASE + "/orders").param("page", "1").param("size", "2").cookie(cookie))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get(BASE + "/orders/" + ownOtherShop).cookie(cookie))
                .andExpect(status().isOk());
        mvc.perform(get(BASE + "/orders/" + foreign).cookie(cookie))
                .andExpect(status().isNotFound());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from customers where phone_e164='+221771234567'",
                                Long.class))
                .isEqualTo(3);
        assertThat(count("user_accounts")).isEqualTo(1);
    }

    @Test
    void unknownPhoneHasSameRequestShapeAndEmptyOrders() throws Exception {
        var known = request("+221771234567");
        var unknown = request("+221781234567");
        assertThat(known.path("message")).isEqualTo(unknown.path("message"));
        assertThat(known.propertyNames()).isEqualTo(unknown.propertyNames());
        Cookie cookie = verify(unknown);
        mvc.perform(get(BASE + "/orders").cookie(cookie))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(BASE + "/orders/" + number).cookie(cookie))
                .andExpect(status().isNotFound());
    }

    @Test
    void failedOtpAttemptsCommitAndBlockEvenCorrectCode() throws Exception {
        var challenge = request("+221771234567");
        UUID id = UUID.fromString(challenge.path("challengeId").asText());
        String wrong = delivery.codes.get(id).equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++)
            postJson("/otp/verify", Map.of("challengeId", id, "code", wrong), null)
                    .andExpect(status().isBadRequest());
        postJson("/otp/verify", Map.of("challengeId", id, "code", delivery.codes.get(id)), null)
                .andExpect(status().isBadRequest());
        assertThat(
                        jdbc.queryForObject(
                                "select attempt_count from otp_challenges where id=?",
                                Integer.class,
                                id))
                .isEqualTo(5);
        assertThat(count("customer_access_sessions")).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select code_hash from otp_challenges where id=?",
                                String.class,
                                id))
                .doesNotContain(delivery.codes.get(id));
    }

    @Test
    void expiredOtpFailsAndConsumedOtpCannotReplay() throws Exception {
        var c = request("+221771234567");
        UUID id = UUID.fromString(c.path("challengeId").asText());
        jdbc.update(
                "update otp_challenges set created_at=current_timestamp-interval '10 minutes',expires_at=current_timestamp-interval '1 minute'");
        postJson("/otp/verify", Map.of("challengeId", id, "code", delivery.codes.get(id)), null)
                .andExpect(status().isBadRequest());
        var fresh = request("+221781234567");
        verify(fresh);
        postJson(
                        "/otp/verify",
                        Map.of(
                                "challengeId",
                                fresh.path("challengeId").asText(),
                                "code",
                                delivery.codes.get(
                                        UUID.fromString(fresh.path("challengeId").asText()))),
                        null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void resendCooldownAndPhoneLimitPersist() throws Exception {
        request("+221771234567");
        postJson("/otp/request", Map.of("phone", "77 123 45 67"), null)
                .andExpect(status().isTooManyRequests());
        for (int i = 0; i < 4; i++) {
            jdbc.update(
                    "update otp_challenges set resend_after=created_at+interval '1 microsecond'");
            request("+221771234567");
        }
        jdbc.update("update otp_challenges set resend_after=created_at+interval '1 microsecond'");
        postJson("/otp/request", Map.of("phone", "+221771234567"), null)
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void ipLimitCannotBeBypassedWithForwardedHeader() throws Exception {
        for (int i = 0; i < 30; i++)
            postJson("/otp/request", Map.of("phone", "+221771234567"), null);
        mvc.perform(
                        post(BASE + "/otp/request")
                                .cookie(csrfCookie)
                                .header("X-XSRF-TOKEN", csrf)
                                .header("X-Forwarded-For", "203.0.113.1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"phone\":\"+221781234567\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void csrfIsRequiredEvenForLoginAndOtpRequest() throws Exception {
        mvc.perform(
                        post(BASE + "/otp/request")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"phone\":\"+221771234567\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CUSTOMER_CSRF_REQUIRED"));
        mvc.perform(
                        post(BASE + "/otp/request")
                                .cookie(csrfCookie)
                                .header("X-XSRF-TOKEN", "wrong")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());
        assertThat(count("otp_challenges")).isZero();
    }

    @Test
    void sessionExpiresAndLogoutRevokes() throws Exception {
        Cookie cookie = phone("+221771234567");
        jdbc.update(
                "update customer_access_sessions set created_at=current_timestamp-interval '2 days',expires_at=current_timestamp-interval '1 day'");
        mvc.perform(get(BASE + "/session").cookie(cookie)).andExpect(status().isUnauthorized());
        Cookie fresh = phone("+221781234567");
        postJson("/logout", Map.of(), fresh)
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        mvc.perform(get(BASE + "/session").cookie(fresh)).andExpect(status().isUnauthorized());
    }

    @Test
    void rotationRevokesPreviousLinkAndItsSessions() throws Exception {
        String old = confirmedLink();
        Cookie oldSession = magic(old);
        Cookie owner = phone("+221771234567");
        postJson("/orders/" + number + "/resend-link", Map.of(), owner)
                .andExpect(status().isNoContent());
        postJson("/magic/exchange", Map.of("token", old), null)
                .andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/session").cookie(oldSession)).andExpect(status().isUnauthorized());
        assertThat(count("order_access_links")).isEqualTo(2);
        String fresh =
                crypto.link(
                        jdbc.queryForObject(
                                "select id from order_access_links where revoked_at is null",
                                UUID.class));
        magic(fresh);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(delivery.types.values())
                                        .contains(NotificationType.ORDER_TRACKING_LINK));
        postJson("/orders/" + number + "/revoke-link", Map.of(), owner)
                .andExpect(status().isNoContent());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from order_access_links where revoked_at is null",
                                Long.class))
                .isZero();
    }

    @Test
    void concurrentOtpVerificationHasOnlyOneWinner() throws Exception {
        var c = request("+221771234567");
        UUID id = UUID.fromString(c.path("challengeId").asText());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> call =
                    () -> {
                        start.await();
                        return postJson(
                                        "/otp/verify",
                                        Map.of("challengeId", id, "code", delivery.codes.get(id)),
                                        null)
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            var a = pool.submit(call);
            var b = pool.submit(call);
            start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 400);
        }
        assertThat(count("customer_access_sessions")).isEqualTo(1);
    }

    @Test
    void confirmedEventIsReliableIdempotentAndNotificationFailureCannotRollbackOrder(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        delivery.fail = true;
        publishConfirmation();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(count("order_access_links")).isEqualTo(1));
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "select count(*) from event_publication where status='FAILED'",
                                                        Long.class))
                                        .isGreaterThan(0));
        assertThat(
                        jdbc.queryForObject(
                                "select status from orders where order_number=?",
                                String.class,
                                number))
                .isEqualTo("CONFIRMED");
        var publication = jdbc.queryForList("select serialized_event from event_publication");
        assertThat(publication.toString()).doesNotContain("+221", "/s/", "sensitive-adapter-error");
        publishConfirmation();
        await().during(Duration.ofMillis(300))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(count("order_access_links")).isEqualTo(1));
        var link = jdbc.queryForObject("select id from order_access_links", UUID.class);
        assertThat(access.link(link)).isPresent();
        delivery.fail = false;
        failedPublications.resubmit(ResubmissionOptions.defaults().withMinAge(Duration.ZERO));
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(delivery.links).hasSize(1));
        assertThat(delivery.types.values()).containsExactly(NotificationType.ORDER_CONFIRMED);
        assertThat(delivery.links.values()).containsExactly(access.link(link).orElseThrow().url());
        assertThat(output.getAll())
                .doesNotContain("sensitive-adapter-error", crypto.link(link), "+221771234567");
    }

    @Test
    void pendingOrderNeverCreatesLinkAndOtpDeliveryFailureRemainsUniform() throws Exception {
        jdbc.update(
                "update orders set status='PENDING_PAYMENT',confirmed_at=null where order_number=?",
                number);
        access.confirmed(org, orderId());
        assertThat(count("order_access_links")).isZero();
        delivery.fail = true;
        postJson("/otp/request", Map.of("phone", "+221771234567"), null)
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Si ce numéro peut recevoir un code, celui-ci a été envoyé."));
        assertThat(count("otp_challenges")).isEqualTo(1);
    }

    @Test
    void completedOrdersCanResendWithinArchiveAndNeverBeyondIt() throws Exception {
        Cookie owner = phone("+221771234567");
        mvc.perform(get(BASE + "/orders/" + number + "/capabilities").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canResendTrackingLink").value(true));
        jdbc.update(
                "update orders set status='COMPLETED',completed_at=current_timestamp where order_number=?",
                number);
        mvc.perform(get(BASE + "/orders/" + number + "/capabilities").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canResendTrackingLink").value(true));
        postJson("/orders/" + number + "/resend-link", Map.of(), owner)
                .andExpect(status().isNoContent());
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(delivery.types.values())
                                        .containsExactly(NotificationType.ORDER_TRACKING_LINK));
        jdbc.update(
                "update orders set completed_at=current_timestamp-interval '91 days',placed_at=current_timestamp-interval '92 days',confirmed_at=current_timestamp-interval '92 days' where order_number=?",
                number);
        mvc.perform(get(BASE + "/orders/" + number + "/capabilities").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canResendTrackingLink").value(false));
        postJson("/orders/" + number + "/resend-link", Map.of(), owner)
                .andExpect(status().isUnauthorized());
        assertThat(count("order_access_links")).isEqualTo(1);
    }

    @Test
    void capabilityHonorsScopeOwnershipAndPendingOrder() throws Exception {
        Cookie scoped = magic(confirmedLink());
        mvc.perform(get(BASE + "/orders/" + number + "/capabilities").cookie(scoped))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canResendTrackingLink").value(false));
        Cookie foreign = phone("+221781234567");
        mvc.perform(get(BASE + "/orders/" + number + "/capabilities").cookie(foreign))
                .andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/orders/" + number + "/capabilities"))
                .andExpect(status().isUnauthorized());
        Cookie owner = phone("+221771234567");
        jdbc.update(
                "update orders set status='PENDING_PAYMENT',confirmed_at=null where order_number=?",
                number);
        mvc.perform(get(BASE + "/orders/" + number + "/capabilities").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canResendTrackingLink").value(false));
        postJson("/orders/" + number + "/resend-link", Map.of(), owner)
                .andExpect(status().isConflict());
    }

    @Test
    void disabledMetaLinksCompletePublicationsWithoutRollbackOrLaterReplay() throws Exception {
        var fixture = new sn.groupia.notification.infrastructure.whatsapp.DisabledLinksFixture();
        delivery.linkDelegate = fixture.gateway;
        publishConfirmation();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            assertThat(count("order_access_links")).isEqualTo(1);
                            assertThat(
                                            jdbc.queryForObject(
                                                    "select count(*) from event_publication where completion_date is null",
                                                    Long.class))
                                    .isZero();
                        });
        assertThat(
                        jdbc.queryForObject(
                                "select status from orders where order_number=?",
                                String.class,
                                number))
                .isEqualTo("CONFIRMED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from event_publication where status='FAILED'",
                                Long.class))
                .isZero();
        String old =
                crypto.link(jdbc.queryForObject("select id from order_access_links", UUID.class));
        magic(old);
        Cookie owner = phone("+221771234567"); // OTP security and recovery remain available.
        postJson("/orders/" + number + "/resend-link", Map.of(), owner)
                .andExpect(status().isNoContent());
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            assertThat(count("order_access_links")).isEqualTo(2);
                            assertThat(
                                            jdbc.queryForObject(
                                                    "select count(*) from event_publication where completion_date is null",
                                                    Long.class))
                                    .isZero();
                        });
        postJson("/magic/exchange", Map.of("token", old), null)
                .andExpect(status().isUnauthorized());
        assertThat(fixture.httpCalls).hasValue(0);
        assertThat(delivery.links).isEmpty();
        delivery.linkDelegate =
                null; // Simulate enabling link delivery after publications have completed.
        failedPublications.resubmit(ResubmissionOptions.defaults().withMinAge(Duration.ZERO));
        publishConfirmation(); // Duplicate fact must not replay a suppressed delivery.
        await().during(Duration.ofMillis(300))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> {
                            assertThat(delivery.links).isEmpty();
                            assertThat(count("order_access_links")).isEqualTo(2);
                        });
        postJson("/orders/" + number + "/resend-link", Map.of(), owner)
                .andExpect(status().isNoContent());
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(delivery.types.values())
                                        .containsExactly(NotificationType.ORDER_TRACKING_LINK));
    }

    private void publishConfirmation() {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        s ->
                                events.publishEvent(
                                        new OrderCommerciallyAccepted(
                                                UUID.randomUUID(),
                                                Instant.now(),
                                                org,
                                                UUID.fromString(campaign.path("id").asText()),
                                                orderId())));
    }

    private String confirmedLink() {
        access.confirmed(org, orderId());
        return crypto.link(
                jdbc.queryForObject(
                        "select id from order_access_links where order_id=? and revoked_at is null",
                        UUID.class,
                        orderId()));
    }

    private UUID orderId() {
        return jdbc.queryForObject(
                "select id from orders where order_number=?", UUID.class, number);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private Cookie magic(String link) throws Exception {
        return postJson("/magic/exchange", Map.of("token", link), null)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("__Host-groupia_customer");
    }

    private JsonNode request(String phone) throws Exception {
        return read(
                postJson("/otp/request", Map.of("phone", phone), null)
                        .andExpect(status().isOk())
                        .andReturn());
    }

    private Cookie phone(String phone) throws Exception {
        return verify(request(phone));
    }

    private Cookie verify(JsonNode challenge) throws Exception {
        UUID id = UUID.fromString(challenge.path("challengeId").asText());
        return postJson(
                        "/otp/verify",
                        Map.of("challengeId", id, "code", delivery.codes.get(id)),
                        null)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("__Host-groupia_customer");
    }

    private ResultActions postJson(String path, Object body, Cookie session) throws Exception {
        var req =
                post(BASE + path)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body));
        if (session != null) req.cookie(session);
        return mvc.perform(req);
    }

    private UUID shop() throws Exception {
        return UUID.fromString(
                read(auth(post("/api/organizations"), Map.of("name", "Boutique")).andReturn())
                        .path("id")
                        .asText());
    }

    private JsonNode campaign(UUID owner) throws Exception {
        var p =
                read(
                        auth(
                                        post("/api/organizations/" + owner + "/products"),
                                        Map.of(
                                                "name",
                                                "Chemise",
                                                "variants",
                                                List.of(
                                                        Map.of(
                                                                "attributes",
                                                                Map.of("size", "M"),
                                                                "sku",
                                                                "SKU-" + UUID.randomUUID(),
                                                                "saleUnit",
                                                                "UNIT"))))
                                .andReturn());
        String base = "/api/organizations/" + owner + "/grouping-campaigns";
        var c =
                read(
                        auth(
                                        post(base),
                                        Map.of(
                                                "title",
                                                "Collection octobre",
                                                "shippingTerms",
                                                Map.of(
                                                        "transportMode",
                                                        "AIR",
                                                        "shippingPricingMethod",
                                                        "FIXED",
                                                        "shippingCollectionMode",
                                                        "ESTIMATED_AT_CHECKOUT",
                                                        "shippingRate",
                                                        1000)))
                                .andReturn());
        c =
                read(
                        auth(
                                        post(base + "/" + c.path("id").asText() + "/offers"),
                                        Map.of(
                                                "campaignVersion",
                                                c.path("version").asLong(),
                                                "productVariantId",
                                                p.path("variants").get(0).path("id").asText(),
                                                "unitPrice",
                                                5000))
                                .andReturn());
        return read(
                auth(
                                post(base + "/" + c.path("id").asText() + "/open"),
                                Map.of("version", c.path("version").asLong()))
                        .andReturn());
    }

    private String order(JsonNode c, String phone) throws Exception {
        var r =
                read(
                        mvc.perform(
                                        post("/api/public/groupings/"
                                                        + c.path("publicIdentifier").asText()
                                                        + "/checkout")
                                                .header(
                                                        "Idempotency-Key",
                                                        UUID.randomUUID().toString())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "contact",
                                                                        Map.of(
                                                                                "firstName",
                                                                                "Awa",
                                                                                "lastName",
                                                                                "Diop",
                                                                                "phone",
                                                                                phone),
                                                                        "lines",
                                                                        List.of(
                                                                                Map.of(
                                                                                        "offerId",
                                                                                        c.path(
                                                                                                        "offers")
                                                                                                .get(
                                                                                                        0)
                                                                                                .path(
                                                                                                        "id")
                                                                                                .asText(),
                                                                                        "quantity",
                                                                                        1,
                                                                                        "expectedUnitPrice",
                                                                                        5000,
                                                                                        "expectedPriceRevision",
                                                                                        0))))))
                                .andExpect(status().isCreated())
                                .andReturn());
        String n = r.path("orderNumber").asText();
        trackingToken = r.path("trackingToken").asText();
        jdbc.update(
                "update orders set status='CONFIRMED',confirmed_at=placed_at where order_number=?",
                n);
        return n;
    }

    private ResultActions auth(MockHttpServletRequestBuilder request, Object body)
            throws Exception {
        return mvc.perform(
                request.header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)));
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
