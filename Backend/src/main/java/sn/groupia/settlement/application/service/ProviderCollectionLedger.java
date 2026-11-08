package sn.groupia.settlement.application.service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.organization.api.*;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.event.PaymentObligationSettled;
import sn.groupia.settlement.api.dto.ProviderCollectionView;

@Service
public class ProviderCollectionLedger {
    private final JdbcTemplate jdbc;
    private final CollectionFacts facts;
    private final OrganizationAccess access;
    private final PaymentOperations payments;

    public ProviderCollectionLedger(
            JdbcTemplate j, CollectionFacts f, OrganizationAccess a, PaymentOperations p) {
        jdbc = j;
        facts = f;
        access = a;
        payments = p;
    }

    public record CollectionPage(
            List<ProviderCollectionView> items,
            UUID nextCursor,
            boolean canSimulatePayout,
            boolean canSimulateRefund) {}

    public record CollectionRefresh(int recorded, UUID nextCursor) {}

    @ApplicationModuleListener
    public void settled(PaymentObligationSettled e) {
        if (e.purpose() == PaymentPurpose.SUBSCRIPTION_MONTHLY) return;
        var f = facts.read(e.organizationId(), e.paymentRequestId());
        var p = f.evidence();
        if (!p.paymentId().equals(e.paymentId())
                || !p.target().equals(e.target())
                || p.purpose() != e.purpose()
                || p.amount().compareTo(e.amount()) != 0
                || !p.currency().equals(e.currency()))
            throw new IllegalStateException("Collection event mismatch");
        record(f);
    }

    @Transactional
    public CollectionRefresh refresh(UUID org, UUID after) {
        access.requirePermission(org, OrganizationPermission.VIEW_PAYMENTS);
        var ids = facts.settledRequests(org, after, 100);
        int count = 0;
        for (var id : ids) count += record(facts.read(org, id));
        return new CollectionRefresh(count, ids.size() == 100 ? ids.getLast() : null);
    }

    @Transactional(readOnly = true)
    public CollectionPage page(UUID org, UUID after) {
        access.requirePermission(org, OrganizationPermission.VIEW_PAYMENTS);
        var rows =
                jdbc.query(
                        "select * from provider_collections where organization_id=? and id>? order by id limit 101",
                        (rs, n) -> {
                            var request = rs.getObject("payment_request_id", UUID.class);
                            var s = payments.requestState(org, request);
                            boolean review =
                                    s.reconciliationRequired()
                                            || s.attemptInProgress()
                                            || s.operationalStatus()
                                                    != PaymentOperationalStatus.SETTLED;
                            return new ProviderCollectionView(
                                    rs.getObject("id", UUID.class),
                                    request,
                                    rs.getString("target_type"),
                                    rs.getObject("target_id", UUID.class),
                                    rs.getString("purpose"),
                                    rs.getString("provider"),
                                    accountReference(rs.getString("provider_account")),
                                    rs.getString("currency"),
                                    rs.getBigDecimal("seller_principal"),
                                    rs.getBigDecimal("platform_fee"),
                                    rs.getBigDecimal("collection_fee"),
                                    rs.getBigDecimal("customer_charged"),
                                    rs.getTimestamp("confirmed_at").toInstant(),
                                    review ? "REVIEW_REQUIRED" : "VERIFIED",
                                    "SASPAY".equals(rs.getString("provider"))
                                            ? "PROVIDER_APPROVAL_REQUIRED"
                                            : "EXISTING_SETTLEMENT_WORKFLOW");
                        },
                        org,
                        after == null ? new UUID(0, 0) : after);
        boolean more = rows.size() > 100;
        var items = more ? rows.subList(0, 100) : rows;
        return new CollectionPage(
                List.copyOf(items),
                more ? items.getLast().id() : null,
                access.hasPermission(org, OrganizationPermission.EXECUTE_PAYOUTS),
                access.hasPermission(org, OrganizationPermission.MANAGE_REFUNDS));
    }

    private int record(CollectionFacts.Fact f) {
        var p = f.evidence();
        if (p.providerAccountReference() == null
                || p.providerAccountReference().isBlank()
                || p.providerTransactionId() == null
                || p.providerTransactionId().isBlank()
                || p.confirmedAt() == null
                || p.status() != PaymentStatus.SUCCEEDED
                || p.acceptanceStatus() != AcceptanceStatus.CLOSED
                || !"XOF".equals(p.currency()))
            throw new IllegalStateException("Incomplete collection source evidence");
        int added =
                jdbc.update(
                        "insert into provider_collections(id,organization_id,payment_request_id,payment_id,target_type,target_id,purpose,provider,provider_account,provider_transaction_id,currency,principal,seller_principal,platform_fee,collection_fee,customer_charged,confirmed_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict(payment_request_id) do nothing",
                        p.paymentRequestId(),
                        p.organizationId(),
                        p.paymentRequestId(),
                        p.paymentId(),
                        p.target().type().name(),
                        p.target().id(),
                        p.purpose().name(),
                        p.provider(),
                        p.providerAccountReference(),
                        p.providerTransactionId(),
                        p.currency(),
                        p.amount(),
                        f.sellerPrincipal(),
                        f.platformFee(),
                        f.collectionFee(),
                        f.customerCharged(),
                        Timestamp.from(p.confirmedAt()));
        if (added == 0
                && !Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "select exists(select 1 from provider_collections where payment_request_id=? and organization_id=? and payment_id=? and target_type=? and target_id=? and purpose=? and provider=? and provider_account=? and provider_transaction_id=? and currency=? and principal=? and seller_principal=? and platform_fee=? and collection_fee is not distinct from cast(? as numeric) and customer_charged is not distinct from cast(? as numeric) and confirmed_at=?)",
                                Boolean.class,
                                p.paymentRequestId(),
                                p.organizationId(),
                                p.paymentId(),
                                p.target().type().name(),
                                p.target().id(),
                                p.purpose().name(),
                                p.provider(),
                                p.providerAccountReference(),
                                p.providerTransactionId(),
                                p.currency(),
                                p.amount(),
                                f.sellerPrincipal(),
                                f.platformFee(),
                                f.collectionFee(),
                                f.customerCharged(),
                                Timestamp.from(p.confirmedAt()))))
            throw new IllegalStateException("Immutable collection mismatch");
        return added;
    }

    public static String accountReference(String account) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(account.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
