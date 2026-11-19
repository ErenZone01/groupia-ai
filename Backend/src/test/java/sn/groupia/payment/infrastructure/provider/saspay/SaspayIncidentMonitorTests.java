package sn.groupia.payment.infrastructure.provider.saspay;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SaspayIncidentMonitorTests {
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    @Test
    void backfillBootstrapAndSixHourChunkNeverSkipForward() {
        Instant earlier = NOW.minus(Duration.ofDays(9));
        assertThat(SaspayIncidentMonitor.bootstrap(NOW, null))
                .isEqualTo(NOW.minus(Duration.ofHours(48)));
        assertThat(SaspayIncidentMonitor.bootstrap(NOW, earlier)).isEqualTo(earlier);
        assertThat(SaspayIncidentMonitor.nextBoundary(earlier, NOW))
                .isEqualTo(earlier.plus(Duration.ofHours(6)));
        assertThat(SaspayIncidentMonitor.nextBoundary(NOW.minus(Duration.ofMinutes(8)), NOW))
                .isEqualTo(NOW);
        assertThatThrownBy(() -> SaspayIncidentMonitor.nextBoundary(NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void incidentsStoreOnlyFingerprintsAndRejectProviderSuppliedErrorCodes() {
        var db = mock(JdbcTemplate.class);
        var monitor =
                new SaspayIncidentMonitor(
                        db,
                        null,
                        SaspayTestConfiguration.properties(),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        "");
        String secretishReference = "synthetic-provider-ref+221771234567";
        assertThat(SaspayIncidentMonitor.fingerprint(secretishReference))
                .matches("[a-f0-9]{64}")
                .doesNotContain(secretishReference);
        assertThat(SaspayIncidentMonitor.fingerprint(secretishReference))
                .isEqualTo(SaspayIncidentMonitor.fingerprint(secretishReference));
        assertThatThrownBy(
                        () -> monitor.recordIncident("free text; DROP TABLE", secretishReference))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(db);
        monitor.recordIncident("WEBHOOK_STUCK", secretishReference);
        verify(db, times(1))
                .update(
                        argThat(
                                sql ->
                                        sql.contains("INSERT INTO saspay_audit_incidents")
                                                && !sql.contains("UPDATE payments")
                                                && !sql.contains("UPDATE merchant_payouts")),
                        any(Object[].class));
    }

    @Test
    void rejectsInvalidOperatorBackfillTimestampBeforeAnyDatabaseAction() {
        var db = mock(JdbcTemplate.class);
        assertThatThrownBy(
                        () ->
                                new SaspayIncidentMonitor(
                                        db,
                                        null,
                                        SaspayTestConfiguration.properties(),
                                        Clock.fixed(NOW, ZoneOffset.UTC),
                                        "yesterday"))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
        verifyNoInteractions(db);
    }
}
