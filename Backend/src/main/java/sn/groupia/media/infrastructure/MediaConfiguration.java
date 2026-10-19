package sn.groupia.media.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import sn.groupia.media.domain.service.*;
import sn.groupia.media.infrastructure.storage.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaProperties.class)
class MediaConfiguration {
    @Bean
    MediaObjectStorage mediaObjectStorage(MediaProperties properties, Environment environment) {
        properties.validate(
                environment.acceptsProfiles(Profiles.of("prod"))
                        || environment.getProperty(
                                "groupia.security.production", Boolean.class, false));
        if (!properties.enabled()) return new DisabledMediaStorage();
        return properties.storageType().equals("filesystem")
                ? new FilesystemMediaStorage(properties.localRoot())
                : new S3MediaStorage(properties);
    }

    private static final class DisabledMediaStorage implements MediaObjectStorage {
        public void put(sn.groupia.media.domain.model.MediaRendition rendition, byte[] bytes) {
            throw new MediaStorageException();
        }

        public java.util.Optional<StoredObject> head(String key) {
            throw new MediaStorageException();
        }

        public void delete(String key) {
            throw new MediaStorageException();
        }

        public String resolveUrl(String key) {
            throw new MediaStorageException();
        }
    }
}
