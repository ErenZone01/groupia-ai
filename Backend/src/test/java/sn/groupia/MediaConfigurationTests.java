package sn.groupia;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MediaConfigurationTests {
    @Test
    void disabledProductionContextStartsWithoutStorageCredentials() throws Exception {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(
                        Class.forName("sn.groupia.media.infrastructure.MediaConfiguration"))
                .withPropertyValues("spring.profiles.active=prod", "groupia.media.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void productionContextRefusesFilesystem() throws Exception {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(
                        Class.forName("sn.groupia.media.infrastructure.MediaConfiguration"))
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "groupia.media.enabled=true",
                        "groupia.media.storage-type=filesystem")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void disabledRequiresNoStorageEvenInProduction() {
        MediaImageTests.properties(Map.of()).validate(true);
    }

    @Test
    void localFilesystemRequiresNoCloud() {
        MediaImageTests.properties(
                        Map.of(
                                "groupia.media.enabled",
                                true,
                                "groupia.media.storage-type",
                                "filesystem"))
                .validate(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"filesystem", "memory", "", "s3"})
    void productionRejectsUnsupportedOrIncompleteStorage(String type) {
        var p =
                MediaImageTests.properties(
                        Map.of("groupia.media.enabled", true, "groupia.media.storage-type", type));
        assertThatThrownBy(() -> p.validate(true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validatesS3AndRedactsCredentials() {
        String secret = UUID.randomUUID().toString();
        var p =
                MediaImageTests.properties(
                        Map.of(
                                "groupia.media.enabled",
                                true,
                                "groupia.media.storage-type",
                                "s3",
                                "groupia.media.s3.endpoint",
                                "https://storage.example.test",
                                "groupia.media.public-base-url",
                                "https://images.example.test",
                                "groupia.media.s3.region",
                                "auto",
                                "groupia.media.s3.bucket",
                                "media-test",
                                "groupia.media.s3.access-key",
                                UUID.randomUUID().toString(),
                                "groupia.media.s3.secret-key",
                                secret));
        p.validate(true);
        assertThat(p.toString()).doesNotContain(secret);
        assertThat(p.s3().toString()).doesNotContain(secret);
        assertThat(p.s3().chunkedEncodingEnabled()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://images.example.test",
                "https://user:password@images.example.test",
                "https://images.example.test/?secret=x",
                "file:///images"
            })
    void rejectsUnsafePublicUrl(String url) {
        var p =
                MediaImageTests.properties(
                        Map.of(
                                "groupia.media.enabled",
                                true,
                                "groupia.media.storage-type",
                                "s3",
                                "groupia.media.s3.endpoint",
                                "https://storage.example.test",
                                "groupia.media.public-base-url",
                                url,
                                "groupia.media.s3.region",
                                "auto",
                                "groupia.media.s3.bucket",
                                "media-test",
                                "groupia.media.s3.access-key",
                                UUID.randomUUID().toString(),
                                "groupia.media.s3.secret-key",
                                UUID.randomUUID().toString()));
        assertThatThrownBy(() -> p.validate(true)).isInstanceOf(IllegalStateException.class);
    }
}
