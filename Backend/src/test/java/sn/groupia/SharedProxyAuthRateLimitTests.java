package sn.groupia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import sn.groupia.identity.infrastructure.hardening.AuthLimitSettings;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "groupia.security.auth-rate-limit.login-network=3",
            "groupia.security.auth-rate-limit.register-network=3",
            "groupia.security.auth-rate-limit.login-subject=2",
            "groupia.security.auth-rate-limit.register-subject=2",
            "groupia.security.auth-rate-limit.cleanup-delay=3600000"
        })
@ActiveProfiles("prod")
@AutoConfigureMockMvc
@Import(HardeningTestConfiguration.class)
class SharedProxyAuthRateLimitTests {
    @DynamicPropertySource
    static void earlySecrets(DynamicPropertyRegistry registry) {
        ProductionHardeningTests.earlySecrets(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthLimitSettings settings;
    @Autowired org.springframework.core.env.Environment environment;

    @BeforeEach
    void reset() {
        jdbc.update("delete from auth_rate_limits");
        for (int i = 0; i < 8; i++) {
            jdbc.update("delete from user_accounts where phone_number=?", phone(i));
        }
        // Exercise the production default, with no network-enabled test override.
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.networkEnabled()).isFalse();
        assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("none");
    }

    private static String phone(int i) {
        return "+22177000200" + i;
    }

    private ResultActions auth(String operation, String phone, int forwarded) throws Exception {
        return mvc.perform(
                post("/api/auth/" + operation)
                        .with(
                                request -> {
                                    request.setRemoteAddr("192.0.2.10");
                                    return request;
                                })
                        .header("X-Forwarded-For", "198.51.100." + forwarded)
                        .header("Forwarded", "for=198.51.100." + forwarded)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"phoneNumber\":\""
                                        + phone
                                        + "\",\"password\":\"Proxy test phrase 2026!\"}"));
    }

    private void assertOnlySubjects(int count) {
        assertThat(jdbc.queryForObject("select count(*) from auth_rate_limits", Integer.class))
                .isEqualTo(count);
        assertThat(jdbc.queryForList("select scope from auth_rate_limits", String.class))
                .allMatch(scope -> scope.endsWith("-subject"));
        assertThat(jdbc.queryForList("select key_hash from auth_rate_limits", String.class))
                .allMatch(hash -> hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void sharedProxyDoesNotBlockDistinctLoginSubjects() throws Exception {
        for (int i = 0; i < 8; i++) {
            auth("login", phone(i), 1).andExpect(status().isUnauthorized());
        }
        assertOnlySubjects(8);
    }

    @Test
    void sharedProxyDoesNotBlockDistinctRegistrations() throws Exception {
        for (int i = 0; i < 5; i++) {
            auth("register", phone(i), 1).andExpect(status().isAccepted());
        }
        assertOnlySubjects(5);
    }

    @Test
    void normalizedSubjectBudgetsRemainActiveDespiteSpoofedForwardedHeaders() throws Exception {
        auth("register", phone(0), 1).andExpect(status().isAccepted());
        auth("register", "77 000 20 00", 2).andExpect(status().isAccepted());
        auth("register", phone(0), 3).andExpect(status().isTooManyRequests());
        auth("login", phone(0), 4).andExpect(status().isOk());
        auth("login", "77 000 20 00", 5).andExpect(status().isOk());
        auth("login", phone(0), 6)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"));
        assertOnlySubjects(2);
        assertThat(jdbc.queryForList("select attempts from auth_rate_limits", Integer.class))
                .containsOnly(2);
    }
}
