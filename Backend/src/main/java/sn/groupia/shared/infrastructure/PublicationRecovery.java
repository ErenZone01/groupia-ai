package sn.groupia.shared.infrastructure;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/** Technical publication registry operations; no business-module dependencies. */
@Component @ConditionalOnProperty(name="groupia.events.recovery.enabled",havingValue="true",matchIfMissing=true)
public class PublicationRecovery {
    private final FailedEventPublications failures;
    private final JdbcTemplate jdbc;
    public PublicationRecovery(FailedEventPublications failures,JdbcTemplate jdbc) { this.failures=failures; this.jdbc=jdbc; }
    @Scheduled(fixedDelayString="${groupia.events.recovery.delay:60000}",initialDelayString="${groupia.events.recovery.delay:60000}")
    public void recover() {
        failures.resubmit(ResubmissionOptions.defaults().withBatchSize(100).withMaxInFlight(20).withMinAge(Duration.ofMinutes(1))
                .withFilter(p->p.getCompletionAttempts()<5));
        Long exhausted=jdbc.queryForObject("select count(*) from event_publication where status='FAILED' and completion_attempts>=5",Long.class);
        if(exhausted!=null && exhausted>0){LoggerFactory.getLogger(getClass()).error("{} event publications require operator investigation",exhausted);}
    }
}
