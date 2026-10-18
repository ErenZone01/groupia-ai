package sn.groupia;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import sn.groupia.identity.domain.repository.AuthLimitBuckets;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "groupia.security.auth-rate-limit.network-enabled=true",
            "groupia.security.auth-rate-limit.login-network=6",
            "groupia.security.auth-rate-limit.login-subject=3",
            "groupia.security.auth-rate-limit.register-network=3",
            "groupia.security.auth-rate-limit.register-subject=2",
            "groupia.security.auth-rate-limit.max-buckets=20",
            "groupia.security.auth-rate-limit.cleanup-delay=3600000"
        })
@ActiveProfiles("prod")
@AutoConfigureMockMvc
@Import(HardeningTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ProductionHardeningTests {
    @org.springframework.test.context.DynamicPropertySource
    static void earlySecrets(org.springframework.test.context.DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        String encoded = Base64.getEncoder().encodeToString(key);
        registry.add("groupia.security.auth-rate-limit.key-base64", () -> encoded);
        registry.add("groupia.security.jwt.secret-base64", () -> encoded);
        registry.add("groupia.monitoring.token", () -> encoded);
        byte[] refresh = new byte[32];
        new java.security.SecureRandom().nextBytes(refresh);
        String refreshEncoded = Base64.getEncoder().encodeToString(refresh);
        registry.add("groupia.security.session.refresh-pepper-base64", () -> refreshEncoded);
        registry.add("groupia.security.session.allowed-origins", () -> "https://groupia.test");
    }

    @Autowired org.springframework.core.env.ConfigurableEnvironment environment;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthLimitBuckets buckets;

    @Value("${groupia.monitoring.token}")
    String monitoring;

    @org.springframework.boot.test.web.server.LocalManagementPort int managementPort;
    private static final String PHONE = "+221770001234", PASSWORD = "Hardening test phrase 2026!";

    @BeforeEach
    void reset() {
        jdbc.update("delete from auth_sessions");
        jdbc.update("delete from auth_rate_limits");
        jdbc.update("delete from user_accounts where phone_number=?", PHONE);
    }

    ResultActions auth(String operation, String phone, String password) throws Exception {
        return mvc.perform(
                post("/api/auth/" + operation)
                        .header("Origin", "https://groupia.test")
                        .header("X-Groupia-Auth", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"phoneNumber\":\""
                                        + phone
                                        + "\",\"password\":\""
                                        + password
                                        + "\"}"));
    }

    @Test
    void loginFailuresAreUniformAndSubjectLimitIsEnforced() throws Exception {
        auth("register", PHONE, PASSWORD).andExpect(status().isAccepted());
        String wrong =
                auth("login", PHONE, "wrong password")
                        .andExpect(status().isUnauthorized())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String absent =
                auth("login", "+221780001234", "wrong password")
                        .andExpect(status().isUnauthorized())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var json = new tools.jackson.databind.ObjectMapper();
        assertThat(json.readTree(wrong).get("code")).isEqualTo(json.readTree(absent).get("code"));
        assertThat(json.readTree(wrong).get("message"))
                .isEqualTo(json.readTree(absent).get("message"));
        auth("login", PHONE, "wrong password").andExpect(status().isUnauthorized());
        auth("login", "77 000 12 34", PASSWORD).andExpect(status().isOk());
        auth("login", PHONE, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"));
    }

    @Test
    void expiredWindowAllowsNewAttempt() throws Exception {
        for (int i = 0; i < 3; i++)
            auth("login", PHONE, PASSWORD).andExpect(status().isUnauthorized());
        auth("login", PHONE, PASSWORD).andExpect(status().isTooManyRequests());
        jdbc.update("update auth_rate_limits set expires_at=CURRENT_TIMESTAMP-interval '1 second'");
        auth("login", PHONE, PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void forwardedHeadersCannotBypassNetworkBudgetAndMalformedJsonIsCounted() throws Exception {
        for (int i = 0; i < 8; i++)
            mvc.perform(
                            post("/api/auth/login")
                                    .header("Origin", "https://groupia.test")
                                    .header("X-Groupia-Auth", "1")
                                    .header("X-Forwarded-For", "198.51.100." + i)
                                    .header("Forwarded", "for=198.51.100." + i)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{"))
                    .andExpect(status().is(i < 6 ? 400 : 429));
        assertThat(jdbc.queryForObject("select count(*) from auth_rate_limits", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void registerIsGenericBoundedAndDoesNotLogCredentials(CapturedOutput output) throws Exception {
        auth("register", PHONE, PASSWORD)
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
        auth("register", PHONE, PASSWORD)
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
        auth("register", PHONE, PASSWORD).andExpect(status().isTooManyRequests());
        auth("register", "+221780009876", PASSWORD).andExpect(status().isTooManyRequests());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from user_accounts where phone_number=?",
                                Integer.class,
                                PHONE))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("select key_hash from auth_rate_limits", String.class))
                .allMatch(k -> k.matches("[0-9a-f]{64}"));
        assertThat(output.getAll()).doesNotContain(PHONE, PASSWORD, monitoring);
    }

    @Test
    void concurrentIndependentTransactionsCannotExceedBudget() throws Exception {
        String key = "a".repeat(64);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var work = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 24; i++)
                work.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return buckets.consume(
                                            "login-subject", key, 5, Duration.ofMinutes(1), 20);
                                }));
            start.countDown();
            int accepted = 0;
            for (var result : work) if (result.get(20, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(5);
        }
        assertThat(
                        jdbc.queryForObject(
                                "select attempts from auth_rate_limits where key_hash=?",
                                Integer.class,
                                key))
                .isEqualTo(5);
    }

    @Test
    void concurrentDistinctBucketsCannotExceedStorageCapacity() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var work = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 48; i++) {
                String key = String.format("%064x", i);
                work.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return buckets.consume(
                                            "login-subject", key, 5, Duration.ofMinutes(1), 20);
                                }));
            }
            start.countDown();
            int accepted = 0;
            for (var result : work) if (result.get(20, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(20);
        }
        assertThat(jdbc.queryForObject("select count(*) from auth_rate_limits", Integer.class))
                .isEqualTo(20);
        assertThat(jdbc.queryForObject("select sum(attempts) from auth_rate_limits", Integer.class))
                .isEqualTo(20);
    }

    @Test
    void storageIsBoundedAndExpiredBucketsAreCleaned() {
        for (int i = 0; i < 20; i++)
            assertThat(
                            buckets.consume(
                                    "login-network",
                                    String.format("%064x", i),
                                    1,
                                    Duration.ofMinutes(1),
                                    20))
                    .isTrue();
        assertThat(buckets.consume("login-network", "f".repeat(64), 1, Duration.ofMinutes(1), 20))
                .isFalse();
        jdbc.update("update auth_rate_limits set expires_at=CURRENT_TIMESTAMP-interval '1 second'");
        assertThat(buckets.cleanup()).isEqualTo(20);
        assertThat(buckets.consume("login-network", "f".repeat(64), 1, Duration.ofMinutes(1), 20))
                .isTrue();
    }

    @Test
    void productionHeadersProtectErrorsAndHstsRequiresActualTls() throws Exception {
        mvc.perform(get("/api/organizations"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists("Permissions-Policy"))
                .andExpect(
                        header().string(
                                        "Content-Security-Policy",
                                        org.hamcrest.Matchers.containsString(
                                                "frame-ancestors 'none'")))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
        mvc.perform(get("/api/organizations").header("X-Forwarded-Proto", "https"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
        mvc.perform(get("/api/organizations").secure(true))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000"));
    }

    @Test
    void productionDocsAndSensitiveActuatorEndpointsAreClosed() throws Exception {
        assertThat(
                        environment.getPropertySources().stream()
                                .map(org.springframework.core.env.PropertySource::getName))
                .noneMatch(name -> name.contains(".env"));
        for (String path :
                List.of(
                        "/v3/api-docs",
                        "/swagger-ui/index.html",
                        "/actuator/env",
                        "/actuator/beans",
                        "/actuator/modulith",
                        "/actuator/metrics"))
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus())
                    .isIn(401, 403, 404);
        var health = management("/actuator/health", false);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).doesNotContain("components", "details");
        assertThat(management("/actuator/metrics", false).statusCode()).isEqualTo(401);
        assertThat(management("/actuator/metrics", true).statusCode()).isEqualTo(200);
        assertThat(management("/actuator/env", true).statusCode()).isIn(401, 403);
    }

    private java.net.http.HttpResponse<String> management(String path, boolean authorized)
            throws Exception {
        var request =
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://127.0.0.1:" + managementPort + path));
        if (authorized) request.header("Authorization", "Bearer " + monitoring);
        return java.net.http.HttpClient.newHttpClient()
                .send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    @Autowired io.micrometer.core.instrument.MeterRegistry meters;

    @Test
    void operationalMetricsHaveNoCustomerOrOrganizationLabels() {
        for (String name :
                List.of(
                        "groupia.events.failed",
                        "groupia.events.exhausted",
                        "groupia.payments.review",
                        "groupia.webhooks.review",
                        "groupia.webhooks.exhausted",
                        "groupia.payouts.review",
                        "groupia.payouts.pending")) {
            var gauge = meters.get(name).gauge();
            assertThat(gauge.value()).isGreaterThanOrEqualTo(0);
            assertThat(gauge.getId().getTags()).isEmpty();
        }
    }
}
