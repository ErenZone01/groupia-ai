package sn.groupia.directsales.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** Historical physical facts, independent of a later payment dispute. No actor data is public. */
public record DirectOrderProgress(Instant paymentConfirmedAt, Instant preparingAt, Instant readyAt,
        Instant dispatchedAt, Instant completedAt) {
    static DirectOrderProgress read(ResultSet rs) throws SQLException {
        return new DirectOrderProgress(time(rs, "payment_confirmed_at"), time(rs, "preparing_at"),
                time(rs, "ready_at"), time(rs, "dispatched_at"), time(rs, "completed_at"));
    }

    private static Instant time(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
