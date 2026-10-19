package sn.groupia.media.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sn.groupia.media.application.service.ProductMediaService;

@Component
@ConditionalOnProperty(name = "groupia.media.enabled", havingValue = "true")
class MediaCleanupJob {
    private final ProductMediaService service;

    MediaCleanupJob(ProductMediaService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${groupia.media.cleanup-delay:60000}",
            initialDelayString = "${groupia.media.cleanup-delay:60000}")
    void run() {
        service.cleanup();
    }
}
