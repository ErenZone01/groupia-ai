package sn.groupia;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));
    }

    @Bean
    DynamicPropertyRegistrar testJwtConfiguration() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String encoded = Base64.getEncoder().encodeToString(secret);
        byte[] magicKey = new byte[32], otpKey = new byte[32];
        new SecureRandom().nextBytes(magicKey);
        new SecureRandom().nextBytes(otpKey);
        String magicEncoded = Base64.getEncoder().encodeToString(magicKey);
        String otpEncoded = Base64.getEncoder().encodeToString(otpKey);
        byte[] refreshKey = new byte[32];
        new SecureRandom().nextBytes(refreshKey);
        String refreshEncoded = Base64.getEncoder().encodeToString(refreshKey);
        return registry -> {
            registry.add("groupia.security.session.refresh-pepper-base64", () -> refreshEncoded);
            registry.add("groupia.security.session.allowed-origins", () -> "https://groupia.test");
            registry.add("groupia.security.auth-rate-limit.enabled", () -> "false");
            registry.add("groupia.payment.bictorys.enabled", () -> "false");
            registry.add("groupia.ordering.expiration.enabled", () -> "false");
            registry.add("groupia.events.recovery.enabled", () -> "false");
            registry.add("groupia.payment.webhook-retry.enabled", () -> "false");
            registry.add("groupia.ordering.platform-fee-xof", () -> "300");
            registry.add("groupia.ordering.payment-window-minutes", () -> "30");
            registry.add("groupia.ordering.tracking-lifetime-days", () -> "180");
            registry.add("groupia.security.jwt.secret-base64", () -> encoded);
            registry.add("groupia.customer-access.magic-key-base64", () -> magicEncoded);
            registry.add("groupia.customer-access.otp-pepper-base64", () -> otpEncoded);
            registry.add("groupia.security.jwt.issuer", () -> "https://groupia.test");
            registry.add("groupia.security.jwt.audience", () -> "groupia-api-test");
            registry.add("groupia.security.jwt.access-token-ttl", () -> "15m");
        };
    }
}
