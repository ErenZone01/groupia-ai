package sn.groupia;

import static org.assertj.core.api.Assertions.*;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.payment.api.*;
import sn.groupia.payment.domain.repository.PaymentDashboardStore;
import sn.groupia.payment.domain.service.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PaymentDashboardParityTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentDashboardStore dashboard;
    @Autowired EntityManager em;
    final Instant at = Instant.parse("2026-10-12T12:00:00Z");
    UUID org, order, request, principal;

    @BeforeEach
    void setup() {
        // Transaction-local copies also permit corrupt combinations that production constraints
        // reject.
        // No production table, migration, trigger or constraint is changed by these parity
        // fixtures.
        for (String table : List.of("payment_requests", "payments", "payment_reviews"))
            jdbc.execute(
                    "create temporary table "
                            + table
                            + " on commit drop as select * from public."
                            + table
                            + " with no data");
        org = UUID.randomUUID();
        order = UUID.randomUUID();
        request = request(org, order, "INITIAL_ORDER", "OPEN", 500, at.plusSeconds(1));
    }

    UUID request(
            UUID organization,
            UUID orderId,
            String purpose,
            String status,
            int amount,
            Instant expiry) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
            insert into payment_requests(id,organization_id,order_id,purpose,business_reference,expected_amount,
                goods_amount,fee_amount,shipping_amount,currency,acceptance_status,expires_at,created_at,version)
            values (?,?,?,?,?,?,?,0,0,'XOF',?,?,?,0)
            """,
                id,
                organization,
                orderId,
                purpose,
                "fixture-" + id,
                amount,
                amount,
                status,
                expiry == null ? null : Timestamp.from(expiry),
                Timestamp.from(at.minusSeconds(100)));
        return id;
    }

    UUID attempt(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
            insert into payments(id,organization_id,payment_request_id,provider,provider_account_reference,
                merchant_reference,provider_transaction_id,provider_idempotency_key,initiation_key,expected_amount,currency,
                confirmed_amount,confirmed_currency,status,reconciliation_required,initiated_at,confirmed_at,version)
            values (?,?,?,'SIMULATED','fixture',?,?,?, ?,500,'XOF',?,?,?,false,?,?,0)
            """,
                id,
                org,
                request,
                "GP-" + id,
                "TX-" + id,
                id.toString(),
                id.toString(),
                status.equals("SUCCEEDED") ? 500 : null,
                status.equals("SUCCEEDED") ? "XOF" : null,
                status,
                Timestamp.from(at.minusSeconds(50)),
                status.equals("SUCCEEDED") ? Timestamp.from(at) : null);
        return id;
    }

    void settled() {
        principal = attempt("SUCCEEDED");
        jdbc.update(
                "update payment_requests set acceptance_status='CLOSED',settled_payment_id=?,closed_at=? where id=?",
                principal,
                Timestamp.from(at),
                request);
    }

    UUID review(UUID attempt, boolean resolved) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
            insert into payment_reviews(id,organization_id,payment_request_id,principal_payment_id,anomalous_payment_id,
                reason,status,anomalous_provider,anomalous_provider_account_reference,anomalous_provider_transaction_id,
                observed_amount,observed_currency,detected_at,version)
            values (?,?,?,?,?,'DUPLICATE_PROVIDER_SUCCESS',?,'SIMULATED','fixture',?,500,'XOF',?,0)
            """,
                id,
                org,
                request,
                principal,
                attempt,
                resolved ? "RESOLVED_REFUNDED" : "OPEN",
                "TX-" + attempt,
                Timestamp.from(at));
        return id;
    }

    PaymentDashboardPolicy.Classification parity(String expected) {
        em.clear();
        var facts = dashboard.facts(List.of(request));
        var r = facts.requests().getFirst();
        var java =
                PaymentDashboardPolicy.classify(
                        r, PaymentRisk.reasons(r, facts.attempts(), facts.reviews()), at);
        var sql = dashboard.list(org, null, null, order, 0, 100, at);
        assertThat(java.status().name()).isEqualTo(expected);
        assertThat(sql.rows())
                .singleElement()
                .satisfies(row -> assertThat(row.status()).isEqualTo(java.status()));
        for (var category : PaymentOperationalStatus.values()) {
            var filtered = dashboard.list(org, category, null, order, 0, 100, at);
            assertThat(filtered.total()).isEqualTo(category == java.status() ? 1 : 0);
            assertThat(filtered.rows()).hasSize(category == java.status() ? 1 : 0);
        }
        return java;
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "CREATED", "PENDING", "FAILED", "CANCELLED", "EXPIRED"})
    void payableAttemptsArePending(String status) {
        if (!status.equals("NONE")) attempt(status);
        parity("PENDING");
    }

    @Test
    void healthyPrincipalIsSettled() {
        settled();
        parity("SETTLED");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "PROVIDER_REVERSAL",
                "PROVIDER_STATE_UNCERTAIN",
                "TRANSACTION_MISMATCH",
                "OTHER"
            })
    void reconciliationAlwaysRequiresReview(String reason) {
        settled();
        jdbc.update(
                "update payments set reconciliation_required=true,reconciliation_reason=? where id=?",
                reason,
                principal);
        parity("REVIEW_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLOSED", "CANCELLED", "SUPERSEDED", "DEADLINE"})
    void inProgressOnNonPayableRequestIsDangerous(String status) {
        if (status.equals("CLOSED")) settled();
        else if (status.equals("DEADLINE"))
            jdbc.update(
                    "update payment_requests set expires_at=? where id=?",
                    Timestamp.from(at),
                    request);
        else
            jdbc.update(
                    "update payment_requests set acceptance_status=? where id=?", status, request);
        attempt("PENDING");
        parity("REVIEW_REQUIRED");
    }

    @ParameterizedTest
    @CsvSource({"CANCELLED,CANCELLED", "SUPERSEDED,SUPERSEDED", "OPEN,DEADLINE_EXCEEDED"})
    void terminalWithoutRisk(String status, String reason) {
        jdbc.update(
                "update payment_requests set acceptance_status=?,expires_at=? where id=?",
                status,
                Timestamp.from(at),
                request);
        assertThat(parity("TERMINAL_UNPAID").terminalReason()).isEqualTo(reason);
        assertThat(
                        jdbc.queryForObject(
                                "select acceptance_status from payment_requests where id=?",
                                String.class,
                                request))
                .isEqualTo(status);
    }

    @Test
    void zeroOpenIsReviewEvenWhenExpired() {
        jdbc.update(
                "update payment_requests set expected_amount=0,goods_amount=0,expires_at=? where id=?",
                Timestamp.from(at),
                request);
        parity("REVIEW_REQUIRED");
    }

    @Test
    void nullDeadlineIsPayable() {
        jdbc.update(
                "update payment_requests set purpose='SHIPPING_BALANCE',expires_at=null where id=?",
                request);
        parity("PENDING");
    }

    @Test
    void missingPrincipalIsReview() {
        jdbc.update("update payment_requests set acceptance_status='CLOSED' where id=?", request);
        parity("REVIEW_REQUIRED");
    }

    @Test
    void fallbackCombinationIsReview() {
        jdbc.update(
                "update payment_requests set expected_amount=-1,goods_amount=-1 where id=?",
                request);
        parity("REVIEW_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AMOUNT", "CURRENCY", "TRANSACTION", "PRINCIPAL_STATUS", "ALLOCATION"})
    void inconsistentFactsAreReview(String variant) {
        settled();
        switch (variant) {
            case "AMOUNT" ->
                    jdbc.update("update payments set confirmed_amount=499 where id=?", principal);
            case "CURRENCY" ->
                    jdbc.update(
                            "update payments set confirmed_currency='EUR' where id=?", principal);
            case "TRANSACTION" ->
                    jdbc.update(
                            "update payments set provider_transaction_id='  ' where id=?",
                            principal);
            case "PRINCIPAL_STATUS" ->
                    jdbc.update("update payments set status='FAILED' where id=?", principal);
            case "ALLOCATION" ->
                    jdbc.update(
                            "update payment_requests set acceptance_status='OPEN' where id=?",
                            request);
            default -> throw new AssertionError();
        }
        parity("REVIEW_REQUIRED");
    }

    @Test
    void unallocatedSuccessIsReview() {
        attempt("SUCCEEDED");
        parity("REVIEW_REQUIRED");
    }

    @Test
    void exactDuplicateAndLastResolutionRestoreHealthyClassification() {
        settled();
        UUID a = attempt("SUCCEEDED"), b = attempt("SUCCEEDED");
        UUID ra = review(a, false), rb = review(b, false);
        parity("REVIEW_REQUIRED");
        assertThat(dashboard.list(org, null, null, null, 0, 1, at).summary().openReviews())
                .isEqualTo(2);
        assertThat(dashboard.list(org, null, null, null, 0, 1, at).summary().reviewRequired())
                .isEqualTo(1);
        jdbc.update("update payment_reviews set status='RESOLVED_REFUNDED' where id=?", ra);
        parity("REVIEW_REQUIRED");
        jdbc.update("update payment_reviews set status='RESOLVED_REFUNDED' where id=?", rb);
        parity("SETTLED");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "TRANSACTION",
                "AMOUNT",
                "CURRENCY",
                "ACCOUNT",
                "PROVIDER",
                "PRINCIPAL",
                "ATTEMPT",
                "REASON"
            })
    void nearMatchRefundNeverNeutralizesDuplicate(String variant) {
        settled();
        UUID a = attempt("SUCCEEDED"), r = review(a, true);
        String column =
                switch (variant) {
                    case "TRANSACTION" -> "anomalous_provider_transaction_id";
                    case "AMOUNT" -> "observed_amount";
                    case "CURRENCY" -> "observed_currency";
                    case "ACCOUNT" -> "anomalous_provider_account_reference";
                    case "PROVIDER" -> "anomalous_provider";
                    case "PRINCIPAL" -> "principal_payment_id";
                    case "ATTEMPT" -> "anomalous_payment_id";
                    default -> "reason";
                };
        Object value =
                variant.equals("AMOUNT")
                        ? 499
                        : variant.equals("PRINCIPAL") || variant.equals("ATTEMPT")
                                ? UUID.randomUUID()
                                : variant.equals("CURRENCY") ? "EUR" : "OTHER";
        jdbc.update("update payment_reviews set " + column + "=? where id=?", value, r);
        parity("REVIEW_REQUIRED");
    }

    @Test
    void exactRefundWithAnotherOpenReviewRemainsReview() {
        settled();
        UUID a = attempt("SUCCEEDED");
        review(a, true);
        review(principal, false);
        parity("REVIEW_REQUIRED");
    }

    @Test
    void nullAccountsMatchExactly() {
        settled();
        UUID a = attempt("SUCCEEDED"), r = review(a, true);
        jdbc.update("update payments set provider_account_reference=null where id=?", a);
        jdbc.update(
                "update payment_reviews set anomalous_provider_account_reference=null where id=?",
                r);
        parity("SETTLED");
    }

    @Test
    void filtersPrecedePaginationAndCountsIgnoreCategoryAndPage() {
        var pendingIds = new ArrayList<UUID>();
        pendingIds.add(request);
        for (int i = 0; i < 24; i++)
            pendingIds.add(
                    request(
                            org,
                            UUID.randomUUID(),
                            "INITIAL_ORDER",
                            "OPEN",
                            500,
                            at.plusSeconds(1)));
        UUID shippingOrder = UUID.randomUUID();
        request(org, shippingOrder, "SHIPPING_BALANCE", "CANCELLED", 500, null);
        request(UUID.randomUUID(), UUID.randomUUID(), "INITIAL_ORDER", "OPEN", 500, null);
        var p0 = dashboard.list(org, PaymentOperationalStatus.PENDING, null, null, 0, 20, at);
        var p1 = dashboard.list(org, PaymentOperationalStatus.PENDING, null, null, 1, 20, at);
        assertThat(p0.total()).isEqualTo(25);
        assertThat(p0.rows()).hasSize(20);
        assertThat(p1.rows()).hasSize(5);
        assertThat(p1.summary()).isEqualTo(p0.summary());
        assertThat(p0.summary().terminalUnpaid()).isEqualTo(1);
        var actual = new ArrayList<UUID>();
        p0.rows().forEach(r -> actual.add(r.id()));
        p1.rows().forEach(r -> actual.add(r.id()));
        pendingIds.sort(Comparator.comparing(UUID::toString));
        assertThat(actual).containsExactlyElementsOf(pendingIds);
        assertThat(
                        dashboard
                                .list(org, null, PaymentPurpose.SHIPPING_BALANCE, null, 0, 20, at)
                                .total())
                .isEqualTo(1);
        assertThat(dashboard.list(org, null, null, shippingOrder, 0, 20, at).total()).isEqualTo(1);
        assertThat(dashboard.list(org, null, null, UUID.randomUUID(), 0, 20, at).rows()).isEmpty();
        assertThat(dashboard.list(org, null, null, null, 50, 20, at).summary())
                .isEqualTo(p0.summary());
    }
}
