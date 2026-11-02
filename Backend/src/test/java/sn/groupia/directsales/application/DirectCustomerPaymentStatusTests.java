package sn.groupia.directsales.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import sn.groupia.shared.api.ApiException;

class DirectCustomerPaymentStatusTests {
    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private static final String TOKEN =
            Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DirectCustomerPaymentStatus subject =
            new DirectCustomerPaymentStatus(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

    private void found(String acceptance, BigDecimal expected, Long accepted, Instant expiry)
            throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("order_number")).thenReturn("DIR-000000000001");
        when(row.getString("currency")).thenReturn("XOF");
        when(row.getObject("accepted_total_xof", Long.class)).thenReturn(accepted);
        when(row.getString("acceptance_status")).thenReturn(acceptance);
        when(row.getBigDecimal("expected_amount")).thenReturn(expected);
        when(row.getTimestamp("expires_at"))
                .thenReturn(expiry == null ? null : Timestamp.from(expiry));
        when(jdbc.query(
                        contains("where d.recovery_token_hash=?"),
                        any(RowMapper.class),
                        anyString()))
                .thenAnswer(
                        invocation -> {
                            RowMapper<?> mapper = invocation.getArgument(1);
                            return List.of(mapper.mapRow(row, 0));
                        });
    }

    @Test
    void preparedObligationIsNotYetPayable() throws Exception {
        found("OPEN", BigDecimal.valueOf(10300), 10300L, NOW.plusSeconds(600));

        var result = subject.lookup(TOKEN);
        assertThat(result.orderNumber()).isEqualTo("DIR-000000000001");
        assertThat(result.preparationStatus()).isEqualTo("PREPARED");
        assertThat(result.acceptedTotalXof()).isEqualTo(10300L);
        assertThat(result.paymentAvailable()).isFalse();
        verify(jdbc)
                .query(
                        contains("where d.recovery_token_hash=?"),
                        any(RowMapper.class),
                        eq(DirectAccessTokens.hashForCreate(TOKEN)));
    }

    @Test
    void historicalOrderRemainsNotPrepared() throws Exception {
        found(null, null, null, null);

        var status = subject.lookup(TOKEN);
        assertThat(status.preparationStatus()).isEqualTo("NOT_PREPARED");
        assertThat(status.paymentAvailable()).isFalse();
    }

    @Test
    void expirationMismatchAndClosedAcceptanceAreNeverPayable() throws Exception {
        found("OPEN", BigDecimal.valueOf(10300), 10300L, NOW.minusSeconds(1));
        assertThat(subject.lookup(TOKEN).preparationStatus()).isEqualTo("EXPIRED");

        found("OPEN", BigDecimal.valueOf(10301), 10300L, NOW.plusSeconds(300));
        assertThat(subject.lookup(TOKEN).preparationStatus()).isEqualTo("UNAVAILABLE");

        found("CLOSED", BigDecimal.valueOf(10300), 10300L, NOW.plusSeconds(300));
        assertThat(subject.lookup(TOKEN).preparationStatus()).isEqualTo("UNAVAILABLE");
        assertThat(subject.lookup(TOKEN).paymentAvailable()).isFalse();
    }

    @Test
    void invalidOrMissingTokenCannotEnumerateOrders() {
        assertThatThrownBy(() -> subject.lookup("DIR-000000000001"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("introuvable");
        verifyNoInteractions(jdbc);

        assertThatThrownBy(() -> subject.lookup(null)).isInstanceOf(ApiException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void wellFormedButUnknownTokenReturnsSameNotFound() {
        assertThatThrownBy(() -> subject.lookup(TOKEN))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("introuvable");
    }
}
