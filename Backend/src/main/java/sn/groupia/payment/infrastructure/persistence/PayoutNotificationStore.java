package sn.groupia.payment.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.payment.api.event.PayoutStatusNotificationReceived;

@Repository
public class PayoutNotificationStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public PayoutNotificationStore(
            JdbcTemplate jdbc, Clock clock, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public UUID receive(
            String account, String hash, boolean authentic, UUID transaction, String error) {
        UUID id = UUID.randomUUID();
        var now = clock.instant();
        int inserted =
                jdbc.update(
                        """
                insert into payout_status_notifications
                (id,provider,provider_account_reference,fingerprint,authentic,provider_transaction_id,error_code,received_at)
                values (?,'SASPAY',?,?,?,?,?,?)
                on conflict(provider,provider_account_reference,fingerprint,authentic) do nothing
                """,
                        id,
                        account,
                        hash,
                        authentic,
                        transaction,
                        error,
                        Timestamp.from(now));
        if (inserted == 0)
            return jdbc.queryForObject(
                    """
                select id from payout_status_notifications where provider='SASPAY'
                and provider_account_reference=? and fingerprint=? and authentic=?
                """,
                    UUID.class,
                    account,
                    hash,
                    authentic);
        if (authentic && error == null && transaction != null)
            events.publishEvent(
                    new PayoutStatusNotificationReceived(id, now, "SASPAY", account, transaction));
        return id;
    }
}
