package sn.groupia;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Let durable consumers finish before fixture TRUNCATE takes table-wide locks. */
final class TestEventDrain {
    private TestEventDrain() {}

    static void awaitIdle(JdbcTemplate jdbc) {
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(25))
                .until(
                        () ->
                                jdbc.queryForObject(
                                                "select count(*) from event_publication where status in ('PUBLISHED','PROCESSING','RESUBMITTED')",
                                                Long.class)
                                        == 0);
    }
}
