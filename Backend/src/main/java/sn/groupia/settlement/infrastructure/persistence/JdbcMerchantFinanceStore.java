package sn.groupia.settlement.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import sn.groupia.settlement.domain.model.*;
import sn.groupia.settlement.domain.repository.MerchantFinanceStore;
import sn.groupia.shared.api.ApiException;

@Repository
class JdbcMerchantFinanceStore implements MerchantFinanceStore {
    private final JdbcTemplate jdbc;

    JdbcMerchantFinanceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant at(ResultSet r, String field) throws SQLException {
        var t = r.getTimestamp(field);
        return t == null ? null : t.toInstant();
    }

    private static UUID id(ResultSet r, String field) throws SQLException {
        return r.getObject(field, UUID.class);
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private final RowMapper<MerchantLedgerEntry> ledger =
            (r, n) ->
                    new MerchantLedgerEntry(
                            id(r, "id"),
                            id(r, "organization_id"),
                            id(r, "campaign_id"),
                            id(r, "order_id"),
                            r.getString("type"),
                            r.getString("direction"),
                            r.getBigDecimal("amount"),
                            r.getString("currency"),
                            r.getBigDecimal("platform_fee_amount"),
                            r.getString("business_reference"),
                            r.getString("source_type"),
                            id(r, "source_id"),
                            at(r, "recorded_at"));
    private final RowMapper<MerchantPayoutDestination> destination =
            (r, n) ->
                    new MerchantPayoutDestination(
                            id(r, "id"),
                            id(r, "organization_id"),
                            r.getString("method"),
                            r.getString("phone_e164"),
                            r.getString("holder_name"),
                            r.getString("status"),
                            at(r, "created_at"),
                            at(r, "updated_at"),
                            id(r, "created_by"),
                            id(r, "updated_by"),
                            r.getLong("version"));
    private final RowMapper<MerchantPayout> payout =
            (r, n) ->
                    new MerchantPayout(
                            id(r, "id"),
                            id(r, "organization_id"),
                            id(r, "campaign_id"),
                            r.getBigDecimal("amount"),
                            r.getString("currency"),
                            r.getString("status"),
                            id(r, "destination_id"),
                            r.getString("method"),
                            r.getString("phone_e164"),
                            r.getString("holder_name"),
                            r.getString("provider"),
                            r.getString("provider_account_reference"),
                            r.getString("business_reference"),
                            id(r, "provider_idempotency_key"),
                            r.getString("provider_transaction_id"),
                            id(r, "requested_by"),
                            at(r, "requested_at"),
                            at(r, "provider_occurred_at"),
                            at(r, "succeeded_at"),
                            r.getBoolean("reconciliation_required"),
                            r.getString("reconciliation_reason"),
                            r.getLong("version"),
                            id(r, "direct_order_id"));

    public void lockOrganization(UUID org) {
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(?,0))",
                (r, n) -> 0,
                "merchant:" + org);
    }

    public void lockTransaction(String provider, String account, String transaction) {
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(?,0))",
                (r, n) -> 0,
                "merchant-transaction:" + provider + ":" + account + ":" + transaction);
    }

    public void append(MerchantLedgerEntry e) {
        jdbc.update(
                """
            insert into merchant_ledger_entries(id,organization_id,campaign_id,order_id,type,direction,amount,currency,platform_fee_amount,
                business_reference,source_type,source_id,recorded_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(business_reference) do nothing
            """,
                e.id(),
                e.organizationId(),
                e.campaignId(),
                e.orderId(),
                e.type(),
                e.direction(),
                e.amount(),
                e.currency(),
                e.platformFeeAmount(),
                e.businessReference(),
                e.sourceType(),
                e.sourceId(),
                ts(e.recordedAt()));
        var existing =
                jdbc.query(
                                "select * from merchant_ledger_entries where business_reference=?",
                                ledger,
                                e.businessReference())
                        .getFirst();
        if (!existing.organizationId().equals(e.organizationId())
                || !existing.campaignId().equals(e.campaignId())
                || !Objects.equals(existing.orderId(), e.orderId())
                || !existing.type().equals(e.type())
                || !existing.direction().equals(e.direction())
                || !existing.sourceType().equals(e.sourceType())
                || !existing.sourceId().equals(e.sourceId())
                || existing.amount().compareTo(e.amount()) != 0
                || existing.platformFeeAmount().compareTo(e.platformFeeAmount()) != 0
                || !existing.currency().equals(e.currency()))
            throw new IllegalStateException("Merchant ledger source conflict");
    }

    public List<MerchantLedgerEntry> ledger(UUID org) {
        return jdbc.query(
                "select * from merchant_ledger_entries where organization_id=? order by recorded_at,id",
                ledger,
                org);
    }

    public List<MerchantPayout> payouts(UUID org) {
        return jdbc.query(
                "select * from merchant_payouts where organization_id=? order by requested_at desc,id",
                payout,
                org);
    }

    public Optional<MerchantPayoutDestination> destination(UUID org) {
        return jdbc
                .query(
                        "select * from merchant_payout_destinations where organization_id=? and status='ACTIVE'",
                        destination,
                        org)
                .stream()
                .findFirst();
    }

    public void replaceDestination(MerchantPayoutDestination d) {
        jdbc.update(
                "update merchant_payout_destinations set status='DISABLED',updated_at=?,updated_by=?,version=version+1 where organization_id=? and status='ACTIVE'",
                ts(d.createdAt()),
                d.createdBy(),
                d.organizationId());
        jdbc.update(
                """
            insert into merchant_payout_destinations(id,organization_id,method,phone_e164,holder_name,status,created_at,updated_at,created_by,updated_by,version)
            values (?,?,?,?,?,'ACTIVE',?,?,?,?,0)
            """,
                d.id(),
                d.organizationId(),
                d.method(),
                d.phoneE164(),
                d.holderName(),
                ts(d.createdAt()),
                ts(d.updatedAt()),
                d.createdBy(),
                d.updatedBy());
    }

    public Optional<MerchantPayout> byReference(UUID org, String ref) {
        return jdbc
                .query(
                        "select * from merchant_payouts where organization_id=? and business_reference=?",
                        payout,
                        org,
                        ref)
                .stream()
                .findFirst();
    }

    public MerchantPayout payout(UUID org, UUID id) {
        return jdbc
                .query(
                        "select * from merchant_payouts where organization_id=? and id=?",
                        payout,
                        org,
                        id)
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new ApiException(
                                        404,
                                        "MERCHANT_PAYOUT_NOT_FOUND",
                                        "Versement introuvable."));
    }

    public void create(MerchantPayout p) {
        jdbc.update(
                """
            insert into merchant_payouts(id,organization_id,campaign_id,amount,currency,status,destination_id,method,phone_e164,holder_name,
                provider,provider_account_reference,business_reference,provider_idempotency_key,requested_by,requested_at,reconciliation_required,reconciliation_reason,direct_order_id)
            values (?,?,?,?,?,'CREATED',?,?,?,?,?,?,?,?,?,?,true,'AWAITING_DISPATCH',?)
            """,
                p.id(),
                p.organizationId(),
                p.campaignId(),
                p.amount(),
                p.currency(),
                p.destinationId(),
                p.method(),
                p.phoneE164(),
                p.holderName(),
                p.provider(),
                p.providerAccountReference(),
                p.businessReference(),
                p.providerIdempotencyKey(),
                p.requestedBy(),
                ts(p.requestedAt()),
                p.directOrderId());
    }

    /** One immutable provider-confirmed fee per payout, independent of seller ledger entries. */
    @Override
    public void recordProviderFee(UUID payoutId, BigDecimal principal, BigDecimal providerFee) {
        if (payoutId == null
                || principal == null
                || providerFee == null
                || principal.signum() <= 0
                || principal.stripTrailingZeros().scale() > 0
                || providerFee.signum() < 0
                || providerFee.scale() > 2)
            throw new IllegalStateException("PAYOUT_FEE_EVIDENCE_INVALID");
        BigDecimal total = principal.add(providerFee);
        jdbc.update(
                """
                insert into merchant_payout_provider_fees
                    (payout_id,principal_xof,provider_fee_xof,wallet_debited_xof)
                values (?,?,?,?)
                on conflict(payout_id) do nothing
                """,
                payoutId,
                principal,
                providerFee,
                total);
        var recorded =
                jdbc.query(
                                """
                        select principal_xof,provider_fee_xof,wallet_debited_xof
                        from merchant_payout_provider_fees where payout_id=?
                        """,
                                (rs, row) ->
                                        new BigDecimal[] {
                                            rs.getBigDecimal("principal_xof"),
                                            rs.getBigDecimal("provider_fee_xof"),
                                            rs.getBigDecimal("wallet_debited_xof")
                                        },
                                payoutId)
                        .getFirst();
        if (recorded[0].compareTo(principal) != 0
                || recorded[1].compareTo(providerFee) != 0
                || recorded[2].compareTo(total) != 0)
            throw new IllegalStateException("PAYOUT_FEE_EVIDENCE_CONFLICT");
    }

    public void state(
            UUID id,
            String status,
            String tx,
            Instant providerAt,
            Instant succeeded,
            boolean review,
            String reason) {
        jdbc.update(
                """
            update merchant_payouts set status=?,provider_transaction_id=coalesce(?,provider_transaction_id),
                provider_occurred_at=coalesce(?,provider_occurred_at),succeeded_at=coalesce(?,succeeded_at),
                reconciliation_required=?,reconciliation_reason=?,version=version+1 where id=?
            """,
                status,
                tx,
                ts(providerAt),
                ts(succeeded),
                review,
                reason,
                id);
    }

    public boolean transactionTaken(
            String provider, String account, String transaction, UUID except) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists(select 1 from merchant_payouts where provider=? and provider_account_reference=? and provider_transaction_id=? and id<>?)",
                        Boolean.class,
                        provider,
                        account,
                        transaction,
                        except));
    }
}
