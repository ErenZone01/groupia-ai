package sn.groupia;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import sn.groupia.identity.infrastructure.security.SessionSettings;

class ProductionSessionConfigurationTests {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void prodResolvesExternalSessionMappings(boolean secure) {
        var external = externalProperties();
        external.put("GROUPIA_AUTH_COOKIE_SECURE", Boolean.toString(secure));
        var environment = loadProductionConfiguration(external);

        assertThat(environment.getProperty("groupia.security.session.allowed-origins"))
                .isEqualTo(external.get("GROUPIA_AUTH_ALLOWED_ORIGINS"));
        assertThat(environment.getProperty("groupia.security.session.refresh-pepper-base64"))
                .isEqualTo(external.get("GROUPIA_REFRESH_TOKEN_PEPPER_BASE64"));
        // Check false too: a default of true must not conceal a missing external mapping.
        assertThat(environment.getProperty("groupia.security.session.cookie-secure", Boolean.class))
                .isEqualTo(secure);
    }

    @Test
    void prodBindsSessionSettingsWithSecureCookieDefault() {
        var external = externalProperties();
        var environment = loadProductionConfiguration(external);
        var settings =
                Binder.get(environment)
                        .bind("groupia.security.session", SessionSettings.class)
                        .orElseThrow(IllegalStateException::new);

        assertThat(settings.refreshPepperBase64())
                .isEqualTo(external.get("GROUPIA_REFRESH_TOKEN_PEPPER_BASE64"));
        assertThat(settings.allowedOrigins())
                .containsExactly("https://session-config.example.test");
        assertThat(settings.cookieSecure()).isTrue();
        assertThat(environment.getProperty("groupia.security.session.cookie-secure"))
                .isEqualTo("true");
    }

    private StandardEnvironment loadProductionConfiguration(Map<String, Object> external) {
        var environment = new StandardEnvironment();
        // Isolate from workstation secrets and Maven properties. Only public external names
        // are supplied; no direct groupia.security.session.* overrides may hide the mappings.
        environment
                .getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment
                .getPropertySources()
                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("external-session-config", external));
        ConfigDataEnvironmentPostProcessor.applyTo(environment);

        assertThat(environment.getActiveProfiles()).containsExactly("prod");
        assertThat(environment.getProperty("groupia.security.production", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.config.import")).isNull();
        return environment;
    }

    private Map<String, Object> externalProperties() {
        byte[] pepper = new byte[32];
        new SecureRandom().nextBytes(pepper);
        return new HashMap<>(
                Map.of(
                        "spring.profiles.active", "prod",
                        "spring.config.location", "classpath:/application.properties",
                        "GROUPIA_REFRESH_TOKEN_PEPPER_BASE64",
                                Base64.getEncoder().encodeToString(pepper),
                        "GROUPIA_AUTH_ALLOWED_ORIGINS", "https://session-config.example.test"));
    }
}
