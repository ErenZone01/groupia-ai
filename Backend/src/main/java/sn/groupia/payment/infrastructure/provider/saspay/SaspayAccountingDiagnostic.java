package sn.groupia.payment.infrastructure.provider.saspay;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.shared.api.ApiException;

/** Read model of recorded evidence, never a merchant payout eligibility calculation. */
@Service
public class SaspayAccountingDiagnostic {
    private final JdbcTemplate jdbc;
    private final SaspayProperties properties;
    private final SaspayIncidentConsoleService access;
    private final Clock clock;

    public SaspayAccountingDiagnostic(
            JdbcTemplate jdbc,
            SaspayProperties properties,
            SaspayIncidentConsoleService access,
            Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.access = access;
        this.clock = clock;
    }

    public record OrganizationPosition(
            UUID organizationId,
            BigDecimal collectedPrincipal,
            BigDecimal merchantPrincipal,
            BigDecimal subscriptionRevenue,
            BigDecimal unallocatedCollections,
            BigDecimal legacyPlatformFees,
            BigDecimal collectionFees,
            BigDecimal paidPayouts,
            BigDecimal reservedPayouts,
            BigDecimal payoutFees,
            BigDecimal additionalWalletFees,
            BigDecimal refundsDue,
            BigDecimal externallyDeclaredRefunds,
            long mixedAccountRefundCases,
            long missingFeeProofs) {
        public BigDecimal retainedMerchantPrincipal() {
            return merchantPrincipal.subtract(paidPayouts);
        }
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "SaspayAccountingSnapshot")
    public record Snapshot(
            Instant checkedAt,
            String currency,
            List<OrganizationPosition> organizations,
            BigDecimal knownNetWalletFlows,
            BigDecimal retainedMerchantPrincipal,
            BigDecimal merchantDeficits,
            BigDecimal unallocatedCollections,
            BigDecimal subscriptionRevenue,
            BigDecimal reservedPayouts,
            long missingFeeProofs,
            String noteCode) {}

    // Aggregate each source before joining: multiple payments, refunds and payouts
    // for a shop must not multiply each other. Every cash source is account-scoped.
    static final String POSITIONS =
            """
        WITH incoming AS (
          SELECT p.organization_id, sum(p.expected_amount) collected,
            sum(coalesce(c.seller_principal,0)) merchant,
            sum(CASE WHEN i.id IS NOT NULL THEN i.amount ELSE 0 END) subscriptions,
            sum(CASE WHEN c.id IS NULL AND i.id IS NULL THEN p.expected_amount ELSE 0 END) unallocated,
            sum(coalesce(c.platform_fee,0)) legacy_fees,
            sum(coalesce(p.provider_collection_fee,0)) collection_fees,
            count(*) FILTER (WHERE p.provider_collection_fee IS NULL) missing_fees
          FROM payments p
          LEFT JOIN provider_collections c ON c.payment_id=p.id AND c.organization_id=p.organization_id
            AND c.provider=p.provider AND c.provider_account=p.provider_account_reference
          LEFT JOIN subscription_invoices i ON i.settled_payment_id=p.id AND i.organization_id=p.organization_id
            AND i.payment_request_id=p.payment_request_id AND i.status='PAID'
          WHERE p.provider='SASPAY' AND p.provider_account_reference=? AND p.status='SUCCEEDED'
          GROUP BY p.organization_id
        ), outgoing AS (
          SELECT p.organization_id,
            sum(CASE WHEN p.status='SUCCEEDED' THEN p.amount ELSE 0 END) paid,
            sum(CASE WHEN p.status IN ('CREATED','PENDING','REVIEW_REQUIRED') THEN p.amount ELSE 0 END) reserved,
            sum(CASE WHEN p.status='SUCCEEDED' THEN coalesce(f.provider_fee_xof,0) ELSE 0 END) fees,
            sum(CASE WHEN p.status='SUCCEEDED' AND f.fee_charge_mode='ADD_ON' THEN f.provider_fee_xof ELSE 0 END) additional_fees,
            count(*) FILTER (WHERE p.status='SUCCEEDED' AND f.payout_id IS NULL) missing_fees
          FROM merchant_payouts p LEFT JOIN merchant_payout_provider_fees f ON f.payout_id=p.id
          WHERE p.provider='SASPAY' AND p.provider_account_reference=?
          GROUP BY p.organization_id
        ), refund_sources AS (
          SELECT case_id, bool_and(provider='SASPAY' AND provider_account=?) exclusive,
            bool_or(provider='SASPAY' AND provider_account=?) relevant
          FROM commercial_refund_sources GROUP BY case_id
        ), refunds AS (
          SELECT c.organization_id,
            sum(CASE WHEN s.exclusive AND c.status='REFUND_DUE' THEN c.amount_due ELSE 0 END) due,
            sum(CASE WHEN s.exclusive AND c.status='EXTERNALLY_RECORDED' THEN c.amount_due ELSE 0 END) declared,
            count(*) FILTER (WHERE NOT s.exclusive) mixed
          FROM commercial_refund_cases c JOIN refund_sources s ON s.case_id=c.id
          WHERE s.relevant GROUP BY c.organization_id
        ), orgs AS (
          SELECT organization_id FROM incoming UNION SELECT organization_id FROM outgoing
          UNION SELECT organization_id FROM refunds
        )
        SELECT o.organization_id, coalesce(i.collected,0) collected,coalesce(i.merchant,0) merchant,
          coalesce(i.subscriptions,0) subscriptions,coalesce(i.unallocated,0) unallocated,
          coalesce(i.legacy_fees,0) legacy_fees,coalesce(i.collection_fees,0) collection_fees,
          coalesce(p.paid,0) paid,coalesce(p.reserved,0) reserved,coalesce(p.fees,0) fees,
          coalesce(p.additional_fees,0) additional_fees,coalesce(r.due,0) refund_due,
          coalesce(r.declared,0) refund_declared,coalesce(r.mixed,0) mixed,
          coalesce(i.missing_fees,0)+coalesce(p.missing_fees,0) missing_fees
        FROM orgs o LEFT JOIN incoming i USING(organization_id)
          LEFT JOIN outgoing p USING(organization_id) LEFT JOIN refunds r USING(organization_id)
        ORDER BY o.organization_id LIMIT 1001
        """;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 15)
    public Snapshot snapshot() {
        access.requireOperatorAccess();
        String account = properties.merchantId().toString();
        List<OrganizationPosition> rows =
                jdbc.query(
                        POSITIONS,
                        (r, n) ->
                                new OrganizationPosition(
                                        r.getObject("organization_id", UUID.class),
                                        r.getBigDecimal("collected"),
                                        r.getBigDecimal("merchant"),
                                        r.getBigDecimal("subscriptions"),
                                        r.getBigDecimal("unallocated"),
                                        r.getBigDecimal("legacy_fees"),
                                        r.getBigDecimal("collection_fees"),
                                        r.getBigDecimal("paid"),
                                        r.getBigDecimal("reserved"),
                                        r.getBigDecimal("fees"),
                                        r.getBigDecimal("additional_fees"),
                                        r.getBigDecimal("refund_due"),
                                        r.getBigDecimal("refund_declared"),
                                        r.getLong("mixed"),
                                        r.getLong("missing_fees")),
                        account,
                        account,
                        account,
                        account);
        if (rows.size() > 1000)
            throw new ApiException(
                    409,
                    "SASPAY_ACCOUNTING_SCOPE_OVERFLOW",
                    "Plus de 1000 boutiques : un export de rapprochement complet est nécessaire.");
        BigDecimal flows = BigDecimal.ZERO,
                retained = BigDecimal.ZERO,
                deficits = BigDecimal.ZERO,
                unallocated = BigDecimal.ZERO,
                subscriptions = BigDecimal.ZERO,
                reserved = BigDecimal.ZERO;
        long missing = 0;
        for (var row : rows) {
            flows =
                    flows.add(row.collectedPrincipal())
                            .subtract(row.paidPayouts())
                            .subtract(row.additionalWalletFees());
            retained = retained.add(row.retainedMerchantPrincipal().max(BigDecimal.ZERO));
            deficits = deficits.add(row.retainedMerchantPrincipal().negate().max(BigDecimal.ZERO));
            unallocated = unallocated.add(row.unallocatedCollections());
            subscriptions = subscriptions.add(row.subscriptionRevenue());
            reserved = reserved.add(row.reservedPayouts());
            missing += row.missingFeeProofs();
        }
        return new Snapshot(
                clock.instant(),
                "XOF",
                List.copyOf(rows),
                flows,
                retained,
                deficits,
                unallocated,
                subscriptions,
                reserved,
                missing,
                "DIAGNOSTIC_NOT_ACCOUNTING_PROOF");
    }
}
