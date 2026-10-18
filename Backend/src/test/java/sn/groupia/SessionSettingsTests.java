package sn.groupia;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import sn.groupia.identity.infrastructure.security.*;

class SessionSettingsTests {
    String key() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    SessionSettings settings(String pepper, boolean secure, String origin) {
        return new SessionSettings(
                Duration.ofDays(30),
                Duration.ofDays(7),
                Duration.ofSeconds(15),
                pepper,
                secure,
                List.of(origin),
                30,
                Duration.ofMinutes(1));
    }

    @Test
    void requiresExplicitIndependentStrongPepper() {
        for (String bad :
                Arrays.asList(
                        null,
                        "",
                        "invalid-base64",
                        Base64.getEncoder().encodeToString(new byte[16])))
            assertThatThrownBy(
                            () ->
                                    new RefreshSecrets(
                                            settings(bad, true, "https://groupia.test"),
                                            new MockEnvironment()))
                    .isInstanceOf(IllegalArgumentException.class);
        String key = key();
        for (String property :
                List.of(
                        "groupia.security.jwt.secret-base64",
                        "groupia.security.auth-rate-limit.key-base64",
                        "groupia.customer-access.magic-key-base64",
                        "groupia.customer-access.otp-pepper-base64"))
            assertThatThrownBy(
                            () ->
                                    new RefreshSecrets(
                                            settings(key, true, "https://groupia.test"),
                                            new MockEnvironment().withProperty(property, key)))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonExactOriginsAndNonLocalInsecureCookies() {
        for (String origin :
                List.of(
                        "*",
                        "null",
                        "https://*.test",
                        "https://groupia.test/",
                        "https://groupia.test/path",
                        "https://user@groupia.test",
                        "https://groupia.test?x=1"))
            assertThatThrownBy(() -> settings(key(), true, origin))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(key(), false, "https://groupia.test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(settings(key(), false, "http://localhost:4200").cookieName())
                .isEqualTo("groupia_refresh");
        assertThatThrownBy(
                        () ->
                                new RefreshSecrets(
                                        settings(key(), false, "http://localhost:4200"),
                                        new MockEnvironment()
                                                .withProperty(
                                                        "groupia.security.production", "true")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hashesOnlyCanonicalRandomTokensAndSeparatesQuotaDomain() {
        var secrets =
                new RefreshSecrets(
                        settings(key(), true, "https://groupia.test"), new MockEnvironment());
        String a = secrets.generate(), b = secrets.generate();
        assertThat(a).hasSize(43).isNotEqualTo(b);
        assertThat(secrets.tokenHash(a))
                .hasSize(32)
                .isEqualTo(secrets.tokenHash(a))
                .isNotEqualTo(secrets.tokenHash(b));
        for (String invalid : Arrays.asList(null, "", a + "=", a.substring(1), "!".repeat(43)))
            assertThat(secrets.tokenHash(invalid)).isNull();
        assertThat(secrets.quotaKey(UUID.randomUUID())).hasSize(32);
    }

    @Test
    void prodProfileCannotDisableSecureCookieEvenWithFlagOverridden() {
        var environment =
                new MockEnvironment().withProperty("groupia.security.production", "false");
        environment.setActiveProfiles("prod");
        assertThatThrownBy(
                        () ->
                                new RefreshSecrets(
                                        settings(key(), false, "http://localhost:4200"),
                                        environment))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
