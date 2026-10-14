package sn.groupia;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.application.service.*;
import sn.groupia.payment.infrastructure.provider.bictorys.*;
import tools.jackson.databind.*;

@SpringBootTest(
        properties = {
            "groupia.customer-access.enabled=true",
            "groupia.customer-access.public-base-url=https://groupia.test"
        })
@AutoConfigureMockMvc
@Import({
    TestcontainersConfiguration.class,
    PaymentTestConfiguration.class,
    BictorysTestServerConfiguration.class,
    CustomerAccessIntegrationTests.DeliveryConfig.class
})
class PaymentReviewIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired PaymentOperations operations;
    @Autowired PaymentTransactions financial;
    @Autowired PaymentService service;
    @Autowired PaymentTestConfiguration.MutablePaymentClock clock;
    @Autowired PaymentTestConfiguration.SimulatedProvider simulator;
    @Autowired BictorysHttpStub http;
    @Autowired PlatformTransactionManager manager;
    @Autowired CustomerAccessIntegrationTests.FakeDelivery delivery;
    MerchantSettlementFixtures.Fixture fixture;
    UUID request;
    String token;

    @BeforeEach
    void setup() {
        drained();
        jdbc.execute(
                "TRUNCATE organizations,user_accounts,event_publication,merchant_ledger_entries,merchant_payouts,merchant_payout_destinations,payment_refunds,refund_webhook_receipts CASCADE");
        clock.reset();
        simulator.reset();
        http.reset();
        delivery.links.clear();
        delivery.types.clear();
        delivery.fail = false;
        delivery.linkDelegate = null;
        fixture = MerchantSettlementFixtures.seed(jdbc);
        token = token(fixture.actor());
        request = createRequest(PaymentPurpose.INITIAL_ORDER);
    }

    UUID createRequest(PaymentPurpose purpose) {
        boolean initial = purpose == PaymentPurpose.INITIAL_ORDER;
        return new TransactionTemplate(manager)
                .execute(
                        s ->
                                operations
                                        .createRequest(
                                                new CreatePaymentRequest(
                                                        fixture.org(),
                                                        fixture.order(),
                                                        purpose,
                                                        purpose + ":" + UUID.randomUUID(),
                                                        new BigDecimal(initial ? "25300" : "2000"),
                                                        "XOF",
                                                        new BigDecimal(initial ? "20000" : "0"),
                                                        new BigDecimal(initial ? "300" : "0"),
                                                        new BigDecimal(initial ? "5000" : "2000"),
                                                        initial
                                                                ? clock.instant().plusSeconds(1800)
                                                                : null))
                                        .id());
    }

    UUID claim() {
        return financial
                .claim(
                        fixture.org(),
                        request,
                        "BICTORYS",
                        "sandbox",
                        UUID.randomUUID().toString(),
                        new PaymentCheckoutContext(number(), null, null, null))
                .receipt()
                .id();
    }

    String number() {
        return jdbc.queryForObject(
                "select order_number from orders where id=?", String.class, fixture.order());
    }

    Map<String, Object> payload(UUID payment, String transaction, String status) {
        var result = new HashMap<String, Object>();
        result.put("id", transaction);
        result.put(
                "merchantReference",
                jdbc.queryForObject(
                        "select merchant_reference from payments where id=?",
                        String.class,
                        payment));
        result.put("paymentReference", number());
        result.put(
                "amount",
                jdbc.queryForObject(
                        "select expected_amount from payments where id=?",
                        BigDecimal.class,
                        payment));
        result.put("currency", "XOF");
        result.put("status", status);
        result.put("timestamp", clock.instant().toString());
        return result;
    }

    ResultActions send(Map<String, Object> payload) throws Exception {
        return mvc.perform(
                post("/api/payment/webhooks/bictorys")
                        .header("X-Secret-Key", "fixture-webhook-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)));
    }

    void success(UUID id, String transaction) throws Exception {
        send(payload(id, transaction, "succeeded")).andExpect(status().isAccepted());
    }

    UUID principal() {
        return jdbc.queryForObject(
                "select settled_payment_id from payment_requests where id=?", UUID.class, request);
    }

    long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    UUID review() {
        return jdbc.queryForObject("select id from payment_reviews", UUID.class);
    }

    String base() {
        return "/api/organizations/" + fixture.org() + "/payment-reviews";
    }

    ResultActions resolve(UUID id, String ref, String note) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("externalRefundReference", ref);
        body.put("resolutionNote", note);
        return mvc.perform(
                post(base() + "/" + id + "/resolve-refunded")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)));
    }

    JsonNode read(MvcResult result) {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    UUID duplicate() throws Exception {
        UUID id = claim();
        success(id, "TX-A");
        drained();
        success(id, "TX-B");
        return id;
    }

    void drained() {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "select count(*) from event_publication where status in ('PUBLISHED','PROCESSING','RESUBMITTED')",
                                                        Long.class))
                                        .isZero());
    }

    void onlyOneNominalEffect() {
        drained();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from event_publication where event_type like '%PaymentRequestSettled' and listener_id like '%OrderSettlementListener%'",
                                Long.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(distinct serialized_event::jsonb->>'eventId') from event_publication where event_type like '%OrderCommerciallyAccepted'",
                                Long.class))
                .isEqualTo(1);
        assertThat(count("merchant_ledger_entries")).isEqualTo(1);
        assertThat(count("merchant_payouts")).isZero();
        assertThat(count("payment_refunds")).isZero();
        assertThat(delivery.links).hasSize(1);
        assertThat(
                        jdbc.queryForObject(
                                "select status from orders where id=?",
                                String.class,
                                fixture.order()))
                .isEqualTo("CONFIRMED");
        assertThat(http.requests).isEmpty();
    }

    @Test
    void firstSuccessAndNewDeliveryOfSameTransactionHaveNoReview() throws Exception {
        UUID a = claim();
        success(a, "TX-A");
        clock.advance(Duration.ofSeconds(1));
        success(a, "TX-A");
        assertThat(principal()).isEqualTo(a);
        assertThat(count("payment_reviews")).isZero();
        onlyOneNominalEffect();
    }

    @Test
    void distinctAttemptsPreserveBothRealSuccessesAndOnePrincipal() throws Exception {
        UUID b = claim();
        send(payload(b, "TX-B", "failed"));
        UUID a = claim();
        success(a, "TX-A");
        success(b, "TX-B");
        assertThat(principal()).isEqualTo(a);
        assertThat(count("payment_reviews")).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payments where status='SUCCEEDED'",
                                Long.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select anomalous_payment_id from payment_reviews", UUID.class))
                .isEqualTo(b);
        clock.advance(Duration.ofSeconds(1));
        success(b, "TX-B");
        assertThat(count("payment_reviews")).isEqualTo(1);
        onlyOneNominalEffect();
    }

    @Test
    void sameAttemptDifferentTransactionsHaveIndependentSnapshotsAndNoOverwrite() throws Exception {
        UUID a = duplicate();
        clock.advance(Duration.ofSeconds(1));
        success(a, "TX-B");
        success(a, "TX-C");
        assertThat(principal()).isEqualTo(a);
        assertThat(count("payment_reviews")).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select provider_transaction_id from payments where id=?",
                                String.class,
                                a))
                .isEqualTo("TX-A");
        assertThat(
                        jdbc.queryForList(
                                "select anomalous_provider_transaction_id from payment_reviews order by anomalous_provider_transaction_id",
                                String.class))
                .containsExactly("TX-B", "TX-C");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payment_reviews where principal_payment_id=anomalous_payment_id and observed_amount=25300 and observed_currency='XOF'",
                                Long.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payment_webhook_events where processing_status='REVIEW_REQUIRED' and last_error_code='DUPLICATE_PROVIDER_SUCCESS'",
                                Long.class))
                .isEqualTo(3);
        onlyOneNominalEffect();
    }

    @Test
    void previouslyBoundDifferentTransactionOnSecondaryAttemptIsNeverOverwritten()
            throws Exception {
        UUID b = claim();
        send(payload(b, "TX-OLD", "failed"));
        UUID a = claim();
        success(a, "TX-A");
        success(b, "TX-B");
        assertThat(
                        jdbc.queryForObject(
                                "select provider_transaction_id from payments where id=?",
                                String.class,
                                b))
                .isEqualTo("TX-OLD");
        assertThat(jdbc.queryForObject("select status from payments where id=?", String.class, b))
                .isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "select anomalous_provider_transaction_id from payment_reviews",
                                String.class))
                .isEqualTo("TX-B");
        onlyOneNominalEffect();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentDifferentTransactionsAllocateOnce(boolean sameAttempt) throws Exception {
        UUID a = claim();
        UUID b = a;
        if (!sameAttempt) {
            send(payload(a, "TX-A", "failed"));
            b = claim();
        }
        var pa = payload(a, "TX-A", "succeeded");
        var pb = payload(b, "TX-B", "succeeded");
        assertThat(race(() -> send(pa).andReturn(), () -> send(pb).andReturn()))
                .allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(202));
        assertThat(principal()).isIn(a, b);
        assertThat(count("payment_reviews")).isEqualTo(1);
        onlyOneNominalEffect();
    }

    @ParameterizedTest
    @CsvSource({
        "amount,AMOUNT_MISMATCH",
        "currency,CURRENCY_MISMATCH",
        "paymentReference,PAYMENT_REFERENCE_MISMATCH",
        "merchantReference,UNKNOWN_REFERENCE"
    })
    void invalidFinancialEvidenceIsNotADuplicate(String field, String reason) throws Exception {
        UUID a = claim();
        success(a, "TX-A");
        var body = payload(a, "TX-B", "succeeded");
        body.put(field, field.equals("amount") ? 25301 : "WRONG");
        send(body).andExpect(status().isAccepted());
        assertThat(count("payment_reviews")).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payment_webhook_events where last_error_code=?",
                                Long.class,
                                reason))
                .isEqualTo(1);
        assertThat(principal()).isEqualTo(a);
    }

    @Test
    void wrongSecretCannotCreateIncident() throws Exception {
        UUID a = claim();
        success(a, "TX-A");
        mvc.perform(
                        post("/api/payment/webhooks/bictorys")
                                .header("X-Secret-Key", "wrong")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(payload(a, "TX-B", "succeeded"))))
                .andExpect(status().isUnauthorized());
        assertThat(count("payment_reviews")).isZero();
        assertThat(principal()).isEqualTo(a);
    }

    @Test
    void differentTransactionWithoutSettlementStaysMismatch() throws Exception {
        UUID a = claim();
        send(payload(a, "TX-A", "failed"));
        success(a, "TX-B");
        assertThat(principal()).isNull();
        assertThat(count("payment_reviews")).isZero();
        assertThat(jdbc.queryForObject("select reconciliation_reason from payments", String.class))
                .isEqualTo("TRANSACTION_MISMATCH");
    }

    @Test
    void providerTransactionReservedByReviewCannotSettleAnotherOrder() throws Exception {
        duplicate();
        fixture = MerchantSettlementFixtures.seed(jdbc);
        request = createRequest(PaymentPurpose.INITIAL_ORDER);
        UUID other = claim();
        success(other, "TX-B");
        assertThat(principal()).isNull();
        assertThat(count("payment_reviews")).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select reconciliation_reason from payments where id=?",
                                String.class,
                                other))
                .isEqualTo("TRANSACTION_MISMATCH");
    }

    @Test
    void resolutionUsesServerActorTimeAndRetriesNeverReopen() throws Exception {
        UUID a = duplicate();
        UUID review = review();
        clock.advance(Duration.ofMinutes(1));
        var first =
                read(
                        resolve(review, " REF-123 ", " note ")
                                .andExpect(status().isOk())
                                .andExpect(header().string("Cache-Control", "no-store"))
                                .andReturn());
        assertThat(first.path("resolvedBy").asText()).isEqualTo(fixture.actor().toString());
        assertThat(Instant.parse(first.path("resolvedAt").asText())).isEqualTo(clock.instant());
        assertThat(first.path("status").asText()).isEqualTo("RESOLVED_REFUNDED");
        var other = MerchantSettlementFixtures.seed(jdbc);
        jdbc.update(
                "insert into organization_members(id,organization_id,user_account_id,role,status,joined_at) values (?,?,?,'MANAGER','ACTIVE',now())",
                UUID.randomUUID(),
                fixture.org(),
                other.actor());
        token = token(other.actor());
        clock.advance(Duration.ofMinutes(1));
        assertThat(read(resolve(review, "REF-123", "note").andExpect(status().isOk()).andReturn()))
                .isEqualTo(first);
        resolve(review, "REF-999", "note").andExpect(status().isConflict());
        resolve(review, "REF-123", "different").andExpect(status().isConflict());
        success(a, "TX-B");
        assertThat(count("payment_reviews")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from payment_reviews", String.class))
                .isEqualTo("RESOLVED_REFUNDED");
        assertThat(
                        jdbc.queryForObject(
                                "select reconciliation_required from payments where id=?",
                                Boolean.class,
                                a))
                .isFalse();
        onlyOneNominalEffect();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentResolutionsAreAtomic(boolean identical) throws Exception {
        duplicate();
        UUID id = review();
        var results =
                race(
                        () -> resolve(id, "REF-1", " note ").andReturn(),
                        () -> resolve(id, identical ? " REF-1 " : "REF-2", "note").andReturn());
        var statuses = results.stream().map(r -> r.getResponse().getStatus()).toList();
        if (identical) {
            assertThat(statuses).containsOnly(200);
            assertThat(read(results.get(0))).isEqualTo(read(results.get(1)));
        } else assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("select version from payment_reviews", Long.class))
                .isEqualTo(1);
    }

    @Test
    void resolutionAndDuplicateWebhookDoNotReopenIncident() throws Exception {
        UUID a = duplicate();
        UUID id = review();
        clock.advance(Duration.ofSeconds(1));
        var body = payload(a, "TX-B", "succeeded");
        assertThat(
                        race(
                                        () -> resolve(id, "REF", null).andReturn(),
                                        () -> send(body).andReturn())
                                .stream()
                                .map(r -> r.getResponse().getStatus()))
                .containsExactly(200, 202);
        assertThat(jdbc.queryForObject("select status from payment_reviews", String.class))
                .isEqualTo("RESOLVED_REFUNDED");
        assertThat(
                        jdbc.queryForObject(
                                "select reconciliation_required from payments", Boolean.class))
                .isFalse();
        assertThat(count("payment_reviews")).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"OWNER,200,200", "MANAGER,200,200", "VIEWER,200,403", "OPERATOR,403,403"})
    void permissionsAreCheckedForEveryEndpoint(String role, int readStatus, int resolveStatus)
            throws Exception {
        duplicate();
        jdbc.update(
                "update organization_members set role=? where organization_id=?",
                role,
                fixture.org());
        mvc.perform(get(base()).header("Authorization", "Bearer " + token))
                .andExpect(status().is(readStatus));
        mvc.perform(get(base() + "/" + review()).header("Authorization", "Bearer " + token))
                .andExpect(status().is(readStatus));
        resolve(review(), "REF", null).andExpect(status().is(resolveStatus));
    }

    @ParameterizedTest
    @CsvSource({"OWNER,true", "MANAGER,true", "VIEWER,false"})
    void reviewCapabilityIsServerScopedAndFalseAfterResolution(String role, boolean allowed)
            throws Exception {
        duplicate();
        jdbc.update(
                "update organization_members set role=? where organization_id=?",
                role,
                fixture.org());
        mvc.perform(get(base()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].canResolve").value(allowed));
        mvc.perform(get(base() + "/" + review()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canResolve").value(allowed));
        jdbc.update(
                "update organization_members set role='OWNER' where organization_id=?",
                fixture.org());
        resolve(review(), "REF-DASHBOARD", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canResolve").value(false));
        jdbc.update(
                "update organization_members set role=? where organization_id=?",
                role,
                fixture.org());
        mvc.perform(get(base()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].canResolve").value(false));
    }

    @Test
    void contextualReviewFilterNeverLeaksAnotherOrganization() throws Exception {
        duplicate();
        mvc.perform(
                        get(base())
                                .param("paymentRequestId", request.toString())
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        var originalRequest = request;
        fixture = MerchantSettlementFixtures.seed(jdbc);
        token = token(fixture.actor());
        mvc.perform(
                        get(base())
                                .param("paymentRequestId", originalRequest.toString())
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void dashboardCountsIncidentsSeparatelyAndResolutionRefreshesClassification() throws Exception {
        UUID payment = duplicate();
        success(payment, "TX-C");
        String url = "/api/organizations/" + fixture.org() + "/payment-requests";
        var result =
                mvc.perform(get(url).header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.summary.openReviews").value(2))
                        .andExpect(jsonPath("$.summary.reviewRequired").value(1))
                        .andExpect(
                                jsonPath("$.items[0].operationalStatus").value("REVIEW_REQUIRED"))
                        .andReturn();
        assertThat(read(result).path("evaluationAt").asText()).isNotBlank();
        var detail =
                mvc.perform(get(url + "/" + request).header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.request.openReviewCount").value(2))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        for (String field :
                List.of(
                        "providerIdempotencyKey",
                        "initiationKey",
                        "checkoutUrl",
                        "signature",
                        "credential",
                        "token")) assertThat(detail).doesNotContain("\"" + field + "\"");
        var ids = jdbc.queryForList("select id from payment_reviews order by id", UUID.class);
        resolve(ids.getFirst(), "REF-FIRST", null).andExpect(status().isOk());
        mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.summary.reviewRequired").value(1));
        resolve(ids.getLast(), "REF-LAST", null).andExpect(status().isOk());
        mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.summary.settled").value(1))
                .andExpect(jsonPath("$.summary.openReviews").value(0))
                .andExpect(jsonPath("$.items[0].operationalStatus").value("SETTLED"));
        onlyOneNominalEffect();
    }

    @ParameterizedTest
    @CsvSource({"OWNER,200", "MANAGER,200", "VIEWER,200", "OPERATOR,403"})
    void dashboardPermissionsAreIndependentOfMembership(String role, int expected)
            throws Exception {
        jdbc.update(
                "update organization_members set role=? where organization_id=?",
                role,
                fixture.org());
        String url = "/api/organizations/" + fixture.org() + "/payment-requests";
        mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected));
        mvc.perform(get(url + "/" + request).header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected));
    }

    @Test
    void dashboardRejectsInvalidFiltersAndKeepsForeignReadsScoped() throws Exception {
        String url = "/api/organizations/" + fixture.org() + "/payment-requests";
        for (String query :
                List.of(
                        "operationalStatus=BOGUS",
                        "purpose=OTHER",
                        "size=101",
                        "page=-1",
                        "page=1000001",
                        "orderId=not-a-uuid"))
            mvc.perform(get(url + "?" + query).header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        var foreign = MerchantSettlementFixtures.seed(jdbc);
        mvc.perform(
                        get(url).param("orderId", foreign.order().toString())
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        String foreignUrl = "/api/organizations/" + foreign.org() + "/payment-requests";
        mvc.perform(get(foreignUrl).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get(foreignUrl + "/" + request)
                                .header("Authorization", "Bearer " + token(foreign.actor())))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousAndOtherOrganizationCannotReadOrResolve() throws Exception {
        duplicate();
        UUID id = review();
        mvc.perform(get(base())).andExpect(status().isUnauthorized());
        mvc.perform(
                        post(base() + "/" + id + "/resolve-refunded")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"externalRefundReference\":\"REF\"}"))
                .andExpect(status().isUnauthorized());
        var original = fixture;
        fixture = MerchantSettlementFixtures.seed(jdbc);
        token = token(fixture.actor());
        mvc.perform(get(base() + "/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        resolve(id, "REF", null).andExpect(status().isNotFound());
        mvc.perform(
                        get("/api/organizations/" + original.org() + "/payment-reviews")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void listingAndInputValidationDoNotExposeJpaOrAcceptClientAuditFields() throws Exception {
        UUID a = duplicate();
        clock.advance(Duration.ofSeconds(1));
        success(a, "TX-C");
        var list =
                read(
                        mvc.perform(
                                        get(base() + "?status=OPEN&size=1")
                                                .header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk())
                                .andExpect(header().string("Cache-Control", "no-store"))
                                .andReturn());
        assertThat(list.path("totalElements").asInt()).isEqualTo(2);
        assertThat(list.path("items").size()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("anomalousProviderTransactionId").asText())
                .isEqualTo("TX-C");
        UUID id = UUID.fromString(list.path("items").get(0).path("id").asText());
        for (String field : List.of("resolvedBy", "resolvedAt", "amount", "status")) {
            mvc.perform(
                            post(base() + "/" + id + "/resolve-refunded")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            json.writeValueAsString(
                                                    Map.of(
                                                            "externalRefundReference",
                                                            "REF",
                                                            field,
                                                            "forged"))))
                    .andExpect(status().isBadRequest());
        }
        for (String query : List.of("size=101", "page=-1", "status=BOGUS"))
            mvc.perform(get(base() + "?" + query).header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        resolve(id, " ", null).andExpect(status().isBadRequest());
        resolve(id, "x".repeat(161), null).andExpect(status().isBadRequest());
        resolve(id, "REF", "x".repeat(1001)).andExpect(status().isBadRequest());
        resolve(id, "REF", " ").andExpect(status().isOk());
        resolve(id, "REF", null).andExpect(status().isOk());
    }

    @Test
    void initialAndShippingObligationsAreIndependent() throws Exception {
        UUID a = claim();
        success(a, "TX-A");
        drained();
        request = createRequest(PaymentPurpose.SHIPPING_BALANCE);
        UUID b = claim();
        success(b, "TX-B");
        drained();
        assertThat(principal()).isEqualTo(b);
        assertThat(count("payment_reviews")).isZero();
        assertThat(count("merchant_ledger_entries")).isEqualTo(2);
        assertThat(delivery.links).hasSize(1);
    }

    @Test
    void immutablePrincipalCannotBeChangedOrClearedInSql() throws Exception {
        UUID b = claim();
        send(payload(b, "TX-B", "failed"));
        UUID a = claim();
        success(a, "TX-A");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update payment_requests set settled_payment_id=? where id=?",
                                        b,
                                        request))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update payment_requests set settled_payment_id=null,closed_at=null,acceptance_status='OPEN' where id=?",
                                        request))
                .isInstanceOf(DataAccessException.class);
        assertThat(principal()).isEqualTo(a);
    }

    @Test
    void reviewFailureRollsBackSecondarySuccessAndRetainsRetryableReceipt() throws Exception {
        UUID b = claim();
        send(payload(b, "TX-B", "failed"));
        UUID a = claim();
        success(a, "TX-A");
        jdbc.execute(
                "alter table payment_reviews add constraint test_reject_review check(false) not valid");
        try {
            send(payload(b, "TX-B", "succeeded")).andExpect(status().isInternalServerError());
            assertThat(count("payment_reviews")).isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select status from payments where id=?", String.class, b))
                    .isEqualTo("FAILED");
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from payment_webhook_events where processing_status='RETRYABLE_FAILURE'",
                                    Long.class))
                    .isEqualTo(1);
        } finally {
            jdbc.execute("alter table payment_reviews drop constraint test_reject_review");
        }
        assertThat(principal()).isEqualTo(a);
    }

    @Test
    void reviewHistoryAndTerminalResolutionAreProtectedInPostgres() throws Exception {
        duplicate();
        UUID id = review();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update payment_reviews set observed_amount=1 where id=?",
                                        id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from payment_reviews where id=?", id))
                .isInstanceOf(DataAccessException.class);
        resolve(id, "REF", null).andExpect(status().isOk());
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update payment_reviews set external_refund_reference='OTHER' where id=?",
                                        id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update payment_reviews set status='OPEN',resolved_by=null,resolved_at=null,external_refund_reference=null where id=?",
                                        id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void reconciliationUsesTheSameDuplicateDetection() throws Exception {
        simulator.initialStatus = PaymentStatus.FAILED;
        UUID b =
                operations
                        .initiateRequest(
                                fixture.org(),
                                request,
                                "SIMULATED",
                                UUID.randomUUID().toString(),
                                null)
                        .id();
        simulator.initialStatus = PaymentStatus.PENDING;
        UUID a =
                operations
                        .initiateRequest(
                                fixture.org(),
                                request,
                                "SIMULATED",
                                UUID.randomUUID().toString(),
                                null)
                        .id();
        for (UUID id : List.of(a, b)) {
            simulator.status(
                    jdbc.queryForObject(
                            "select merchant_reference from payments where id=?", String.class, id),
                    PaymentStatus.SUCCEEDED);
            service.reconcile(id);
        }
        assertThat(principal()).isEqualTo(a);
        assertThat(count("payment_reviews")).isEqualTo(1);
        service.reconcile(b);
        assertThat(count("payment_reviews")).isEqualTo(1);
    }

    void cloneReview(UUID id, Map<String, Object> changes) {
        var fields = new HashMap<String, Object>(changes);
        fields.put("id", UUID.randomUUID().toString());
        jdbc.update(
                "insert into payment_reviews select (jsonb_populate_record(null::payment_reviews,to_jsonb(r)||?::jsonb)).* from payment_reviews r where id=?",
                json.writeValueAsString(fields),
                id);
    }

    @Test
    void postgresEnforcesIncidentUniquenessAndSnapshotConstraints() throws Exception {
        duplicate();
        UUID id = review();
        assertThatThrownBy(() -> cloneReview(id, Map.of())).isInstanceOf(DataAccessException.class);
        for (var changes :
                List.of(
                        Map.<String, Object>of(
                                "anomalous_provider_transaction_id", "TX-C", "reason", "OTHER"),
                        Map.<String, Object>of(
                                "anomalous_provider_transaction_id", "TX-C", "status", "OTHER"),
                        Map.<String, Object>of(
                                "anomalous_provider_transaction_id",
                                "TX-C",
                                "status",
                                "RESOLVED_REFUNDED"),
                        Map.<String, Object>of(
                                "anomalous_provider_transaction_id",
                                "TX-C",
                                "organization_id",
                                UUID.randomUUID().toString()),
                        Map.<String, Object>of(
                                "anomalous_provider_transaction_id",
                                "TX-C",
                                "payment_request_id",
                                UUID.randomUUID().toString()),
                        Map.<String, Object>of(
                                "anomalous_provider_transaction_id",
                                "TX-C",
                                "principal_payment_id",
                                UUID.randomUUID().toString()),
                        Map.<String, Object>of(
                                "anomalous_provider_transaction_id",
                                "TX-C",
                                "observed_currency",
                                "EUR"),
                        Map.<String, Object>of(
                                "anomalous_provider_transaction_id",
                                "TX-C",
                                "observed_amount",
                                new BigDecimal("25300.5")),
                        Map.<String, Object>of(
                                "anomalous_provider_transaction_id", "TX-C", "version", -1))) {
            assertThatThrownBy(() -> cloneReview(id, changes))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThat(count("payment_reviews")).isEqualTo(1);
    }

    @Test
    void nullProviderAccountStillDeduplicatesInPostgres() throws Exception {
        UUID original = duplicate();
        UUID id = review();
        UUID attempt = UUID.randomUUID();
        var fields = new HashMap<String, Object>();
        fields.put("id", attempt.toString());
        fields.put("provider_account_reference", null);
        fields.put("merchant_reference", "GP-" + attempt);
        fields.put("initiation_key", attempt.toString());
        jdbc.update(
                "insert into payments select (jsonb_populate_record(null::payments,to_jsonb(p)||?::jsonb)).* from payments p where id=?",
                json.writeValueAsString(fields),
                original);
        var changes = new HashMap<String, Object>();
        changes.put("anomalous_payment_id", attempt.toString());
        changes.put("anomalous_provider_account_reference", null);
        cloneReview(id, changes);
        assertThatThrownBy(() -> cloneReview(id, changes)).isInstanceOf(DataAccessException.class);
        assertThat(count("payment_reviews")).isEqualTo(2);
    }

    @Test
    void resolvingOneIncidentDoesNotHideOtherOpenIncidentsOrReversal() throws Exception {
        UUID a = duplicate();
        UUID bReview = review();
        success(a, "TX-C");
        resolve(bReview, "REF-B", null).andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "select reconciliation_required from payments where id=?",
                                Boolean.class,
                                a))
                .isTrue();
        UUID cReview =
                jdbc.queryForObject(
                        "select id from payment_reviews where anomalous_provider_transaction_id='TX-C'",
                        UUID.class);
        jdbc.update("update payments set reconciliation_reason='PROVIDER_REVERSAL' where id=?", a);
        resolve(cReview, "REF-C", null).andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "select reconciliation_reason from payments where id=?",
                                String.class,
                                a))
                .isEqualTo("PROVIDER_REVERSAL");
    }

    @Test
    void serverVerificationPolicyIsStillRequiredForSecondarySuccess() throws Exception {
        UUID a =
                operations
                        .initiateRequest(
                                fixture.org(),
                                request,
                                "SIMULATED",
                                UUID.randomUUID().toString(),
                                null)
                        .id();
        String merchant =
                jdbc.queryForObject(
                        "select merchant_reference from payments where id=?", String.class, a);
        var original = simulator.status(merchant, PaymentStatus.SUCCEEDED);
        service.reconcile(a);
        var secondary =
                new sn.groupia.payment.domain.service.ProviderObservation(
                        original.provider(),
                        original.providerAccountReference(),
                        merchant,
                        "TX-B",
                        request,
                        fixture.order(),
                        PaymentPurpose.INITIAL_ORDER,
                        original.amount(),
                        original.currency(),
                        PaymentStatus.SUCCEEDED,
                        clock.instant());
        for (boolean timeout : List.of(false, true)) {
            simulator.verificationTimeout = timeout;
            byte[] body =
                    json.writeValueAsBytes(
                            new sn.groupia.payment.domain.service.NormalizedWebhook(
                                    UUID.randomUUID().toString(), secondary));
            mvc.perform(
                            post("/api/payment/webhooks/SIMULATED")
                                    .header("X-Test-Signature", simulator.signature(body))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isAccepted());
        }
        assertThat(count("payment_reviews")).isZero();
        assertThat(principal()).isEqualTo(a);
        simulator.verificationTimeout = false;
        simulator.observations.put(merchant, secondary);
        service.reconcile(a);
        assertThat(count("payment_reviews")).isEqualTo(1);
    }

    @Autowired sn.groupia.payment.domain.repository.FinancialStore store;

    @Test
    void pessimisticRefreshReloadsFactsReadBeforeAnotherTransactionCommitted() throws Exception {
        UUID a = claim();
        var read = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var stale =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(manager)
                                            .execute(
                                                    s -> {
                                                        store.request(request).orElseThrow();
                                                        store.payment(a).orElseThrow();
                                                        read.countDown();
                                                        try {
                                                            if (!committed.await(
                                                                    10, TimeUnit.SECONDS))
                                                                throw new IllegalStateException(
                                                                        "Concurrent commit timed out");
                                                        } catch (InterruptedException e) {
                                                            Thread.currentThread().interrupt();
                                                            throw new IllegalStateException(e);
                                                        }
                                                        assertThat(
                                                                        store.lockRequest(request)
                                                                                .getSettledPaymentId())
                                                                .isEqualTo(a);
                                                        return store.lockPayment(a).getStatus();
                                                    }));
            assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                success(a, "TX-A");
            } finally {
                committed.countDown();
            }
            assertThat(stale.get(15, TimeUnit.SECONDS)).isEqualTo(PaymentStatus.SUCCEEDED);
        }
    }

    String token(UUID actor) {
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(),
                                JwtClaimsSet.builder()
                                        .issuer("https://groupia.test")
                                        .audience(List.of("groupia-api-test"))
                                        .subject(actor.toString())
                                        .issuedAt(Instant.now())
                                        .expiresAt(Instant.now().plusSeconds(1800))
                                        .build()))
                .getTokenValue();
    }

    List<MvcResult> race(Callable<MvcResult> a, Callable<MvcResult> b) throws Exception {
        var ready = new CountDownLatch(2);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () -> {
                                ready.countDown();
                                gate.await();
                                return a.call();
                            });
            var second =
                    pool.submit(
                            () -> {
                                ready.countDown();
                                gate.await();
                                return b.call();
                            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            gate.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
    }
}
