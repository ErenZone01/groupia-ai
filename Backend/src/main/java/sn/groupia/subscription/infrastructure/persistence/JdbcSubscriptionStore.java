package sn.groupia.subscription.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import sn.groupia.subscription.domain.model.OrganizationSubscription;
import sn.groupia.subscription.domain.model.SubscriptionBillingContact;
import sn.groupia.subscription.domain.model.SubscriptionPeriod;
import sn.groupia.subscription.domain.repository.SubscriptionStore;

@Repository
class JdbcSubscriptionStore implements SubscriptionStore {
    private static final RowMapper<OrganizationSubscription> SUBSCRIPTION =
            JdbcSubscriptionStore::subscription;
    private static final RowMapper<SubscriptionPeriod> PERIOD = JdbcSubscriptionStore::period;
    private final JdbcTemplate jdbc;

    JdbcSubscriptionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<OrganizationSubscription> find(UUID organizationId) {
        return jdbc
                .query(
                        "select * from organization_subscriptions where organization_id=?",
                        SUBSCRIPTION,
                        organizationId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<OrganizationSubscription> lock(UUID organizationId) {
        return jdbc
                .query(
                        "select * from organization_subscriptions where organization_id=? for update",
                        SUBSCRIPTION,
                        organizationId)
                .stream()
                .findFirst();
    }

    @Override
    public void createTrial(
            UUID organizationId,
            Instant startedAt,
            Instant endsAt,
            String provenance,
            Instant now) {
        jdbc.update(
                """
                insert into organization_subscriptions(id,organization_id,status,trial_started_at,trial_ends_at,
                    trial_provenance,created_at,updated_at,billing_timezone)
                values (?,?, 'TRIALING', ?, ?, ?, ?, ?, (select timezone from organizations where id=?))
                on conflict (organization_id) do nothing
                """,
                UUID.randomUUID(),
                organizationId,
                timestamp(startedAt),
                timestamp(endsAt),
                provenance,
                timestamp(now),
                timestamp(now),
                organizationId);
    }

    @Override
    public Optional<Instant> migrationAppliedAt() {
        return jdbc
                .query(
                        "select applied_at from subscription_migration_metadata where id=1",
                        (rs, row) -> rs.getTimestamp(1).toInstant())
                .stream()
                .findFirst();
    }

    @Override
    public boolean claimProviderSetup(UUID organizationId, UUID setupKey, Instant requestedAt) {
        return jdbc.update(
                        """
                update organization_subscriptions
                   set provider_setup_key=?, provider_setup_requested_at=?, updated_at=?, version=version+1
                 where organization_id=? and provider_setup_key is null and provider_subscription_id is null
                """,
                        setupKey,
                        timestamp(requestedAt),
                        timestamp(requestedAt),
                        organizationId)
                == 1;
    }

    @Override
    public void saveProviderSetup(
            UUID organizationId,
            UUID setupKey,
            String provider,
            String providerCustomerId,
            String providerSubscriptionId,
            Instant now) {
        jdbc.update(
                """
                update organization_subscriptions
                   set provider=?, provider_customer_id=?, provider_subscription_id=?,
                       cancel_at_period_end=false, canceled_at=null,
                       updated_at=?, version=version+1
                 where organization_id=? and provider_setup_key=?
                """,
                provider,
                providerCustomerId,
                providerSubscriptionId,
                timestamp(now),
                organizationId,
                setupKey);
    }

    @Override
    public Optional<SubscriptionBillingContact> findBillingContact(UUID organizationId) {
        return jdbc
                .query(
                        """
                select id,organization_id,name,phone_e164,email,country,locale,updated_at,updated_by,version
                  from subscription_billing_contacts where organization_id=?
                """,
                        (rs, row) ->
                                new SubscriptionBillingContact(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("organization_id", UUID.class),
                                        rs.getString("name"),
                                        rs.getString("phone_e164"),
                                        rs.getString("email"),
                                        rs.getString("country"),
                                        rs.getString("locale"),
                                        instant(rs, "updated_at"),
                                        rs.getObject("updated_by", UUID.class),
                                        rs.getLong("version")),
                        organizationId)
                .stream()
                .findFirst();
    }

    @Override
    public void saveBillingContact(
            UUID organizationId,
            String name,
            String phoneE164,
            String email,
            UUID actorId,
            Instant now) {
        jdbc.update(
                """
                insert into subscription_billing_contacts(id,organization_id,name,phone_e164,email,country,locale,
                    created_at,updated_at,updated_by)
                values (?,?,?,?,?,'SN','fr-FR',?,?,?)
                on conflict (organization_id) do update
                    set name=excluded.name, phone_e164=excluded.phone_e164, email=excluded.email,
                        updated_at=excluded.updated_at, updated_by=excluded.updated_by,
                        version=subscription_billing_contacts.version+1
                """,
                UUID.randomUUID(),
                organizationId,
                name,
                phoneE164,
                email,
                timestamp(now),
                timestamp(now),
                actorId);
    }

    @Override
    public List<SubscriptionPeriod> history(UUID organizationId) {
        return jdbc.query(
                "select * from subscription_periods where organization_id=? order by period_start desc,id",
                PERIOD,
                organizationId);
    }

    @Override
    public Optional<SubscriptionPeriod> latestPaidPeriod(UUID organizationId) {
        return jdbc
                .query(
                        """
                select * from subscription_periods
                 where organization_id=? and status='PAID'
                 order by period_end desc, created_at desc, id desc limit 1
                """,
                        PERIOD,
                        organizationId)
                .stream()
                .findFirst();
    }

    @Override
    public boolean insertPaidPeriod(SubscriptionPeriod period) {
        if (period.subscriptionInvoiceId() != null) {
            jdbc.update(
                    """
                insert into subscription_periods(id,organization_id,subscription_id,plan_code,amount,currency,billing_interval,
                    period_start,period_end,provider,provider_transaction_id,provider_reference,status,paid_at,created_at,
                    subscription_invoice_id,payment_request_id,payment_id,provider_account_reference)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                    period.id(),
                    period.organizationId(),
                    period.subscriptionId(),
                    period.planCode(),
                    period.amount(),
                    period.currency(),
                    period.billingInterval(),
                    timestamp(period.periodStart()),
                    timestamp(period.periodEnd()),
                    period.provider(),
                    period.providerTransactionId(),
                    period.providerReference(),
                    period.status(),
                    timestamp(period.paidAt()),
                    timestamp(period.createdAt()),
                    period.subscriptionInvoiceId(),
                    period.paymentRequestId(),
                    period.paymentId(),
                    period.providerAccountReference());
            return true;
        }
        return jdbc.update(
                        """
                insert into subscription_periods(id,organization_id,subscription_id,plan_code,amount,currency,
                    billing_interval,period_start,period_end,provider,provider_transaction_id,provider_reference,
                    status,paid_at,created_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict do nothing
                """,
                        period.id(),
                        period.organizationId(),
                        period.subscriptionId(),
                        period.planCode(),
                        period.amount(),
                        period.currency(),
                        period.billingInterval(),
                        timestamp(period.periodStart()),
                        timestamp(period.periodEnd()),
                        period.provider(),
                        period.providerTransactionId(),
                        period.providerReference(),
                        period.status(),
                        timestamp(period.paidAt()),
                        timestamp(period.createdAt()))
                == 1;
    }

    @Override
    public void updatePaidSubscription(
            UUID organizationId,
            String planCode,
            java.math.BigDecimal amount,
            Instant periodStart,
            Instant periodEnd,
            String provider,
            Instant now) {
        jdbc.update(
                """
                update organization_subscriptions
                   set current_period_started_at=?, current_period_ends_at=?,
                       plan_code=?, plan_amount=?, currency='XOF', billing_interval='MONTHLY',
                       provider=?,
                       updated_at=?, version=version+1
                 where organization_id=?
                """,
                timestamp(periodStart),
                timestamp(periodEnd),
                planCode,
                amount,
                provider,
                timestamp(now),
                organizationId);
    }

    @Override
    public void scheduleCancellation(UUID organizationId, Instant now) {
        jdbc.update(
                """
                update organization_subscriptions
                   set cancel_at_period_end=true, canceled_at=coalesce(canceled_at,?),
                       updated_at=case when cancel_at_period_end then updated_at else ? end,
                       version=case when cancel_at_period_end then version else version+1 end
                 where organization_id=?
                """,
                timestamp(now),
                timestamp(now),
                organizationId);
    }

    @Override
    public void refreshStatus(UUID organizationId, String status, Instant now) {
        jdbc.update(
                """
                update organization_subscriptions
                   set status=?, updated_at=?, version=version+1
                 where organization_id=? and status<>?
                """,
                status,
                timestamp(now),
                organizationId,
                status);
    }

    private static OrganizationSubscription subscription(ResultSet rs, int row)
            throws SQLException {
        return new OrganizationSubscription(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getString("status"),
                instant(rs, "trial_started_at"),
                instant(rs, "trial_ends_at"),
                rs.getString("trial_provenance"),
                instant(rs, "current_period_started_at"),
                instant(rs, "current_period_ends_at"),
                rs.getString("plan_code"),
                rs.getBigDecimal("plan_amount"),
                rs.getString("currency"),
                rs.getString("billing_interval"),
                rs.getString("provider"),
                rs.getString("provider_customer_id"),
                rs.getString("provider_subscription_id"),
                rs.getString("provider_setup_key") == null
                        ? null
                        : UUID.fromString(rs.getString("provider_setup_key")),
                instant(rs, "provider_setup_requested_at"),
                rs.getBoolean("cancel_at_period_end"),
                instant(rs, "canceled_at"),
                rs.getLong("version"),
                rs.getString("billing_timezone"));
    }

    private static SubscriptionPeriod period(ResultSet rs, int row) throws SQLException {
        return new SubscriptionPeriod(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("subscription_id", UUID.class),
                rs.getString("plan_code"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("billing_interval"),
                instant(rs, "period_start"),
                instant(rs, "period_end"),
                rs.getString("provider"),
                rs.getString("provider_transaction_id"),
                rs.getString("provider_reference"),
                rs.getString("status"),
                instant(rs, "paid_at"),
                instant(rs, "created_at"),
                rs.getObject("subscription_invoice_id", UUID.class),
                rs.getObject("payment_request_id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                rs.getString("provider_account_reference"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
