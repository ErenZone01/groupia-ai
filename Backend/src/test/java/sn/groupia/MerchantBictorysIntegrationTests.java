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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sn.groupia.payment.api.OutgoingPayoutOperations;
import sn.groupia.payment.api.OutgoingPayoutOperations.*;
import sn.groupia.payment.infrastructure.persistence.OutgoingPayoutStore;
import sn.groupia.payment.infrastructure.provider.bictorys.*;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "groupia.settlement.payouts.enabled=true")
@AutoConfigureMockMvc
@Import({
    TestcontainersConfiguration.class,
    PaymentTestConfiguration.class,
    BictorysTestServerConfiguration.class,
    PayoutTestConfiguration.class
})
class MerchantBictorysIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtEncoder encoder;
    @Autowired BictorysHttpStub http;
    @Autowired OutgoingPayoutOperations outgoing;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationEventPublisher events;
    @Autowired Clock clock;
    @Autowired org.springframework.modulith.events.FailedEventPublications failedPublications;
    MerchantSettlementFixtures.Fixture f;
    UUID destination, key;
    String token, tx;

    @BeforeEach
    void setup() throws Exception {
        http.reset();
        f = MerchantSettlementFixtures.seed(jdbc);
        key = UUID.randomUUID();
        tx = UUID.randomUUID().toString();
        jdbc.update(
                "update grouping_campaigns set status='CLOSED',closed_at=now() where id=?",
                f.campaign());
        // Payout eligibility now checks the persisted collection provider.
        var collection =
                MerchantSettlementFixtures.settled(jdbc, f, "INITIAL_ORDER", "BICTORYS", "sandbox");
        jdbc.update(
                """
            insert into merchant_ledger_entries(id,organization_id,campaign_id,order_id,type,direction,amount,currency,platform_fee_amount,business_reference,source_type,source_id,recorded_at)
            values (?,?,?,?,'ORDER_COLLECTION','CREDIT',10000,'XOF',300,?,'PAYMENT_REQUEST',?,now())
            """,
                UUID.randomUUID(),
                f.org(),
                f.campaign(),
                f.order(),
                "fixture:" + key,
                collection);
        token =
                encoder.encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(MacAlgorithm.HS256).build(),
                                        JwtClaimsSet.builder()
                                                .claim("sid", UUID.randomUUID().toString())
                                                .issuer("https://groupia.test")
                                                .audience(List.of("groupia-api-test"))
                                                .subject(f.actor().toString())
                                                .issuedAt(Instant.now())
                                                .expiresAt(Instant.now().plusSeconds(3600))
                                                .build()))
                        .getTokenValue();
        destination =
                UUID.fromString(
                        json.readTree(
                                        auth(
                                                        put(base() + "/payout-destination"),
                                                        Map.of(
                                                                "method",
                                                                "WAVE",
                                                                "phoneE164",
                                                                "+221771234567",
                                                                "holderName",
                                                                "Synthetic holder",
                                                                "confirmed",
                                                                true))
                                                .andExpect(status().isOk())
                                                .andReturn()
                                                .getResponse()
                                                .getContentAsString())
                                .path("id")
                                .asText());
    }

    private String base() {
        return "/api/organizations/" + f.org();
    }

    private String reference() {
        return "MERCHANT_PAYOUT:" + f.org() + ":" + key;
    }

    private String path() {
        return base() + "/grouping-campaigns/" + f.campaign() + "/merchant-payouts";
    }

    private Map<String, Object> evidence() {
        return new HashMap<>(
                Map.of(
                        "id",
                        tx,
                        "merchantReference",
                        reference(),
                        "amount",
                        -10000,
                        "currency",
                        "XOF",
                        "pspName",
                        "wave_money",
                        "customerPhone",
                        "221771234567",
                        "status",
                        "succeeded",
                        "merchantFee",
                        150,
                        "timestamp",
                        "2026-10-17T00:00:00Z"));
    }

    private ResultActions auth(MockHttpServletRequestBuilder request, Object body)
            throws Exception {
        return mvc.perform(
                request.header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(body)));
    }

    private ResultActions request() throws Exception {
        return auth(
                post(path()).header("Idempotency-Key", key),
                Map.of("destinationId", destination, "confirmed", true));
    }

    private UUID id() {
        return jdbc.queryForObject(
                "select id from merchant_payouts where organization_id=?", UUID.class, f.org());
    }

    private String state() {
        return jdbc.queryForObject(
                "select status from merchant_payouts where organization_id=?",
                String.class,
                f.org());
    }

    private int debits() {
        return jdbc.queryForObject(
                "select count(*) from merchant_ledger_entries where organization_id=? and type='MERCHANT_PAYOUT'",
                Integer.class,
                f.org());
    }

    private void webhook(Map<String, Object> body) throws Exception {
        mvc.perform(
                        post("/api/payment/webhooks/bictorys")
                                .header("X-Secret-Key", "fixture-webhook-secret")
                                .contentType("application/json")
                                .content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private void waitState(String expected) {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(state()).isEqualTo(expected));
    }

    private void start() throws Exception {
        http.reply(201, json.writeValueAsString(evidence()));
        request().andExpect(status().isOk());
        assertThat(state()).isEqualTo("PENDING");
        assertThat(debits()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201})
    void ackNeverSettlesAndWebhookSettlesPrincipalOnce(int statusCode) throws Exception {
        var ack = evidence();
        ack.put("status", 0);
        http.reply(statusCode, json.writeValueAsString(ack));
        request().andExpect(status().isOk());
        assertThat(state()).isEqualTo("PENDING");
        assertThat(debits()).isZero();
        var sent = http.requests.getFirst();
        assertThat(sent.path()).isEqualTo("/pay/v1/payouts?payment_type=wave_money");
        assertThat(sent.apiKey()).isEqualTo("test_secret-fixture");
        var body = json.readTree(sent.body());
        assertThat(body.path("transactionType").asText()).isEqualTo("payment");
        assertThat(body.path("amount").decimalValue()).isEqualByComparingTo("10000");
        assertThat(body.path("merchant").path("secretCode").asText())
                .isEqualTo("fixture-merchant-code");
        assertThat(sent.idempotencyKey())
                .isEqualTo(
                        jdbc.queryForObject(
                                "select provider_idempotency_key::text from merchant_payouts where id=?",
                                String.class,
                                id()));
        for (int n = 0; n < 20; n++) webhook(evidence());
        waitState("SUCCEEDED");
        assertThat(debits()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select amount from merchant_ledger_entries where source_id=?",
                                BigDecimal.class,
                                id()))
                .isEqualByComparingTo("10000");
        assertThat(http.requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"amount", "currency", "merchantReference", "pspName", "customerPhone", "id"})
    void contradictoryProofKeepsFundsReserved(String field) throws Exception {
        start();
        var e = evidence();
        e.put(
                field,
                field.equals("amount")
                        ? -9850
                        : field.equals("id") ? "other-transaction" : "wrong");
        webhook(e);
        waitState("REVIEW_REQUIRED");
        assertThat(debits()).isZero();
        if (field.equals("amount"))
            assertThat(
                            jdbc.queryForObject(
                                    "select reconciliation_reason from merchant_payouts where id=?",
                                    String.class,
                                    id()))
                    .isEqualTo("PAYOUT_AMOUNT_SEMANTICS_UNCONFIRMED");
        key = UUID.randomUUID();
        request().andExpect(status().isConflict());
        assertThat(http.requests).hasSize(1);
    }

    @Test
    void timeoutThenWebhookResolvesWithoutSecondPost() throws Exception {
        http.delayed(201, json.writeValueAsString(evidence()), 1500);
        request().andExpect(status().isOk());
        waitState("REVIEW_REQUIRED");
        request().andExpect(status().isOk());
        assertThat(http.requests).hasSize(1);
        assertThat(debits()).isZero();
        webhook(evidence());
        waitState("SUCCEEDED");
        assertThat(debits()).isEqualTo(1);
    }

    @Test
    void webhookBeforeAckConvergesAndReversalPreservesLedger() throws Exception {
        http.delayed(201, json.writeValueAsString(evidence()), 700);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(() -> request().andExpect(status().isOk()));
            await().atMost(Duration.ofSeconds(5)).until(() -> !http.requests.isEmpty());
            webhook(evidence());
            waitState("SUCCEEDED");
            pending.get();
        }
        var reversed = evidence();
        reversed.put("status", "reversed");
        webhook(reversed);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "select count(*) from refund_webhook_receipts where operation_id=? and last_error_code='PAYOUT_REVERSED'",
                                                        Integer.class,
                                                        id()))
                                        .isPositive());
        assertThat(state()).isEqualTo("SUCCEEDED");
        assertThat(debits()).isEqualTo(1);
    }

    @Test
    void reconciliationOnlyGetsAndRetainsAmbiguousAmount() throws Exception {
        start();
        var e = evidence();
        e.put("amount", -9850);
        http.reply(200, json.writeValueAsString(e));
        auth(post(base() + "/merchant-payouts/" + id() + "/reconcile"), Map.of())
                .andExpect(status().isOk());
        waitState("REVIEW_REQUIRED");
        assertThat(http.requests.stream().filter(r -> r.method().equals("POST")).count())
                .isEqualTo(1);
        assertThat(http.requests.getLast().method()).isEqualTo("GET");
        assertThat(debits()).isZero();
        http.reply(200, json.writeValueAsString(evidence()));
        auth(post(base() + "/merchant-payouts/" + id() + "/reconcile"), Map.of())
                .andExpect(status().isOk());
        waitState("SUCCEEDED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "VIEWER", "OPERATOR"})
    void onlyOwnerMayExecuteOrReconcile(String role) throws Exception {
        start();
        jdbc.update(
                "update organization_members set role=? where organization_id=?", role, f.org());
        request().andExpect(status().isForbidden());
        auth(post(base() + "/merchant-payouts/" + id() + "/reconcile"), Map.of())
                .andExpect(status().isForbidden());
        auth(
                        put(base() + "/payout-destination"),
                        Map.of(
                                "method",
                                "WAVE",
                                "phoneE164",
                                "+221771234567",
                                "holderName",
                                "Synthetic",
                                "confirmed",
                                true))
                .andExpect(status().isForbidden());
        assertThat(http.requests).hasSize(1);
    }

    @Test
    void outgoingReceiptPersistenceFailureReturns500AndRetryDebitsOnce() throws Exception {
        start();
        String body = json.writeValueAsString(evidence());
        jdbc.execute(
                "alter table refund_webhook_receipts add constraint test_receipt_failure check (false) not valid");
        try {
            mvc.perform(
                            post("/api/payment/webhooks/bictorys")
                                    .header("X-Secret-Key", "fixture-webhook-secret")
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isInternalServerError());
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from refund_webhook_receipts where operation_id=?",
                                    Long.class,
                                    id()))
                    .isZero();
            assertThat(state()).isEqualTo("PENDING");
            assertThat(debits()).isZero();
        } finally {
            jdbc.execute(
                    "alter table refund_webhook_receipts drop constraint test_receipt_failure");
        }
        webhook(evidence());
        waitState("SUCCEEDED");
        webhook(evidence());
        assertThat(debits()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "invalid",
                "signature-only",
                "timestamp-only",
                "invalid-with-static",
                "duplicate-signature",
                "duplicate-timestamp"
            })
    void rejectedHmacIsAcknowledgedOnlyAfterSecurityReceiptWithoutFinancialEffect(String mode)
            throws Exception {
        start();
        var request =
                post("/api/payment/webhooks/bictorys")
                        .contentType("application/json")
                        .content(json.writeValueAsString(evidence()));
        if (!mode.equals("timestamp-only")) request.header("X-Webhook-Signature", "0".repeat(64));
        if (!mode.equals("signature-only"))
            request.header("X-Webhook-Timestamp", Long.toString(System.currentTimeMillis()));
        if (mode.equals("invalid-with-static"))
            request.header("X-Secret-Key", "fixture-webhook-secret");
        if (mode.equals("duplicate-signature"))
            request.header("X-Webhook-Signature", "0".repeat(64));
        if (mode.equals("duplicate-timestamp"))
            request.header("X-Webhook-Timestamp", Long.toString(System.currentTimeMillis()));
        mvc.perform(request).andExpect(status().isOk());
        var receipt =
                jdbc.queryForMap(
                        "select authentic, processing_status, last_error_code from refund_webhook_receipts where operation_id=?",
                        id());
        assertThat(receipt.get("authentic")).isEqualTo(false);
        assertThat(receipt.get("processing_status")).isEqualTo("REVIEW_REQUIRED");
        assertThat(receipt.get("last_error_code")).isEqualTo("PAYMENT_AUTHENTICITY_FAILED");
        assertThat(state()).isEqualTo("PENDING");
        assertThat(debits()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from event_publication where event_type like '%OutgoingPayoutEvidenceReceived' and serialized_event like ?",
                                Long.class, "%" + id() + "%"))
                .isZero();
    }

    @Test
    void conflictingAuthenticationHeadersNeverMutateMoney() throws Exception {
        start();
        for (var headers :
                List.of(new String[] {"wrong", "fixture-webhook-secret"}, new String[] {"wrong"}))
            mvc.perform(
                            post("/api/payment/webhooks/bictorys")
                                    .header("X-Secret-Key", (Object[]) headers)
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(evidence())))
                    .andExpect(status().isOk());
        mvc.perform(
                        post("/api/payment/webhooks/bictorys")
                                .header("X-Secret-Key", "fixture-webhook-secret")
                                .header("X-Webhook-Signature", "0".repeat(64))
                                .contentType("application/json")
                                .content(json.writeValueAsString(evidence())))
                .andExpect(status().isOk());
        assertThat(state()).isEqualTo("PENDING");
        assertThat(debits()).isZero();
    }

    @Test
    void independentInstancesCannotAssignSameTransactionToDifferentFamilies() throws Exception {
        var template = new TransactionTemplate(transactions);
        UUID refund = UUID.randomUUID(), merchant = UUID.randomUUID();
        template.executeWithoutResult(
                s -> {
                    for (var entry :
                            Map.of(Kind.REFUND, refund, Kind.MERCHANT_PAYOUT, merchant).entrySet())
                        outgoing.register(
                                new Registration(
                                        entry.getKey(),
                                        entry.getValue(),
                                        f.org(),
                                        "BICTORYS",
                                        "sandbox",
                                        (entry.getKey() == Kind.REFUND
                                                        ? "GPR-"
                                                        : "MERCHANT_PAYOUT:")
                                                + entry.getValue(),
                                        UUID.randomUUID(),
                                        BigDecimal.TEN,
                                        "XOF",
                                        "WAVE",
                                        clock.instant()));
                });
        var a =
                new OutgoingPayoutStore(
                        new JdbcTemplate(jdbc.getDataSource()), json, events, clock);
        var b =
                new OutgoingPayoutStore(
                        new JdbcTemplate(jdbc.getDataSource()), json, events, clock);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return template.execute(s -> a.bind(Kind.REFUND, refund, tx));
                            });
            var second =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return template.execute(
                                        s -> b.bind(Kind.MERCHANT_PAYOUT, merchant, tx));
                            });
            assertThat(Arrays.asList(first.get(), second.get()))
                    .containsExactlyInAnyOrder(null, "PAYOUT_TRANSACTION_ALREADY_USED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAVE", "ORANGE_MONEY"})
    void operatorMappingUsesSnapshot(String method) throws Exception {
        if (method.equals("ORANGE_MONEY"))
            destination =
                    UUID.fromString(
                            json.readTree(
                                            auth(
                                                            put(base() + "/payout-destination"),
                                                            Map.of(
                                                                    "previousDestinationId",
                                                                    destination,
                                                                    "method",
                                                                    method,
                                                                    "phoneE164",
                                                                    "+221771234567",
                                                                    "holderName",
                                                                    "Synthetic holder",
                                                                    "confirmed",
                                                                    true))
                                                    .andReturn()
                                                    .getResponse()
                                                    .getContentAsString())
                                    .path("id")
                                    .asText());
        var e = evidence();
        e.put("pspName", method.equals("WAVE") ? "wave_money" : "orange_money");
        http.reply(201, json.writeValueAsString(e));
        request().andExpect(status().isOk());
        assertThat(http.requests.getFirst().path()).endsWith("payment_type=" + e.get("pspName"));
        webhook(e);
        waitState("SUCCEEDED");
        assertThat(debits()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing-id",
                "wrong-amount",
                "wrong-reference",
                "wrong-currency",
                "wrong-operator",
                "wrong-phone",
                "invalid-json",
                "html",
                "oversized",
                "redirect",
                "401",
                "403",
                "400",
                "500",
                "disconnect"
            })
    void uncertainAcknowledgementsNeverReleaseOrSettle(String defect) throws Exception {
        var e = evidence();
        int code = 201;
        String body;
        switch (defect) {
            case "missing-id" -> e.remove("id");
            case "wrong-amount" -> e.put("amount", -9850);
            case "wrong-reference" -> e.put("merchantReference", "unknown");
            case "wrong-currency" -> e.put("currency", "EUR");
            case "wrong-operator" -> e.put("pspName", "orange_money");
            case "wrong-phone" -> e.put("customerPhone", "221770000009");
            case "redirect" -> code = 302;
            case "401", "403", "400", "500" -> code = Integer.parseInt(defect);
            default -> {}
        }
        body = json.writeValueAsString(e);
        if (defect.equals("invalid-json")) body = "{";
        if (defect.equals("html")) body = "<html>synthetic WAF</html>";
        if (defect.equals("oversized")) body = "x".repeat(65537);
        if (defect.equals("disconnect")) http.disconnect();
        else http.reply(code, body);
        request().andExpect(status().isOk());
        assertThat(state()).isEqualTo("REVIEW_REQUIRED");
        assertThat(debits()).isZero();
        request().andExpect(status().isOk());
        assertThat(http.requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentHumanRequestsHaveOneReservationAndOnePost(boolean sameKey) throws Exception {
        UUID other = sameKey ? key : UUID.randomUUID();
        http.delayed(201, json.writeValueAsString(evidence()), 400);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return request().andReturn().getResponse().getStatus();
                            });
            var b =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return auth(
                                                post(path()).header("Idempotency-Key", other),
                                                Map.of(
                                                        "destinationId",
                                                        destination,
                                                        "confirmed",
                                                        true))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            if (sameKey) assertThat(List.of(a.get(), b.get())).containsOnly(200);
            else assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(http.requests).hasSize(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from merchant_payouts where organization_id=?",
                                Integer.class,
                                f.org()))
                .isEqualTo(1);
    }

    @Test
    void failedConsumerRollsBackMoneyAndDurableReceiptCanBeReplayed() throws Exception {
        start();
        jdbc.execute(
                "alter table merchant_ledger_entries add constraint fixture_outgoing_failure check(type<>'MERCHANT_PAYOUT' or organization_id<>'"
                        + f.org()
                        + "'::uuid)");
        try {
            webhook(evidence());
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(
                            () ->
                                    assertThat(
                                                    jdbc.queryForObject(
                                                            "select count(*) from event_publication where status='FAILED' and listener_id like '%MerchantPayoutEvidenceListener%'",
                                                            Integer.class))
                                            .isPositive());
            assertThat(state()).isEqualTo("PENDING");
            assertThat(debits()).isZero();
        } finally {
            jdbc.execute(
                    "alter table merchant_ledger_entries drop constraint fixture_outgoing_failure");
        }
        failedPublications.resubmit(
                org.springframework.modulith.events.ResubmissionOptions.defaults()
                        .withMinAge(Duration.ZERO));
        waitState("SUCCEEDED");
        assertThat(debits()).isEqualTo(1);
    }

    @Test
    void latePendingWebhookRecoversTransactionForGetWithoutReleasingReservation() throws Exception {
        http.delayed(201, json.writeValueAsString(evidence()), 1500);
        request().andExpect(status().isOk());
        assertThat(state()).isEqualTo("REVIEW_REQUIRED");
        var pending = evidence();
        pending.put("status", "pending");
        webhook(pending);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "select provider_transaction_id from merchant_payouts where id=?",
                                                        String.class,
                                                        id()))
                                        .isEqualTo(tx));
        assertThat(state()).isEqualTo("REVIEW_REQUIRED");
        assertThat(debits()).isZero();
        http.reply(200, json.writeValueAsString(evidence()));
        auth(post(base() + "/merchant-payouts/" + id() + "/reconcile"), Map.of())
                .andExpect(status().isOk());
        waitState("SUCCEEDED");
        assertThat(debits()).isEqualTo(1);
        assertThat(
                        http.requests.stream()
                                .filter(r -> r.path().startsWith("/pay/v1/payouts"))
                                .count())
                .isEqualTo(1);
    }

    @Test
    void validHmacWebhookSettlesWithoutStaticSecret() throws Exception {
        start();
        byte[] body = json.writeValueAsBytes(evidence());
        String timestamp = Long.toString(System.currentTimeMillis());
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(
                new javax.crypto.spec.SecretKeySpec(
                        "fixture-webhook-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(
                        post("/api/payment/webhooks/bictorys")
                                .header("X-Webhook-Timestamp", timestamp)
                                .header(
                                        "X-Webhook-Signature",
                                        HexFormat.of().formatHex(mac.doFinal(body)))
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isOk());
        waitState("SUCCEEDED");
        assertThat(debits()).isEqualTo(1);
    }

    @Test
    void concurrentWebhookAndReconciliationConvergeToOneDebit() throws Exception {
        start();
        http.delayed(200, json.writeValueAsString(evidence()), 400);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var reconciliation =
                    pool.submit(
                            () ->
                                    auth(
                                                    post(
                                                            base()
                                                                    + "/merchant-payouts/"
                                                                    + id()
                                                                    + "/reconcile"),
                                                    Map.of())
                                            .andExpect(status().isOk()));
            await().atMost(Duration.ofSeconds(5)).until(() -> http.requests.size() == 2);
            webhook(evidence());
            reconciliation.get();
        }
        waitState("SUCCEEDED");
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "select count(*) from refund_webhook_receipts where operation_id=? and processing_status='PENDING'",
                                                        Integer.class,
                                                        id()))
                                        .isZero());
        assertThat(debits()).isEqualTo(1);
        assertThat(
                        http.requests.stream()
                                .filter(r -> r.path().startsWith("/pay/v1/payouts"))
                                .count())
                .isEqualTo(1);
    }

    @Test
    void reconciliationBeforeAckCannotDispatchAgain() throws Exception {
        http.delayed(201, json.writeValueAsString(evidence()), 700);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var initiation = pool.submit(() -> request().andExpect(status().isOk()));
            await().atMost(Duration.ofSeconds(5)).until(() -> !http.requests.isEmpty());
            auth(post(base() + "/merchant-payouts/" + id() + "/reconcile"), Map.of())
                    .andExpect(status().isOk())
                    .andExpect(
                            jsonPath("$.reconciliationReason")
                                    .value("PAYOUT_REFERENCE_UNAVAILABLE"));
            initiation.get();
        }
        assertThat(state()).isEqualTo("REVIEW_REQUIRED");
        assertThat(http.requests).hasSize(1);
        assertThat(debits()).isZero();
        webhook(evidence());
        waitState("SUCCEEDED");
        assertThat(debits()).isEqualTo(1);
    }

    @Test
    void destinationChangeAndReservationCannotMixSnapshots() throws Exception {
        http.reply(201, json.writeValueAsString(evidence()));
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var initiation =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return request().andReturn().getResponse().getStatus();
                            });
            var change =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return auth(
                                                put(base() + "/payout-destination"),
                                                Map.of(
                                                        "previousDestinationId",
                                                        destination,
                                                        "method",
                                                        "ORANGE_MONEY",
                                                        "phoneE164",
                                                        "+221770000000",
                                                        "holderName",
                                                        "Another synthetic holder",
                                                        "confirmed",
                                                        true))
                                        .andExpect(status().isOk());
                            });
            int result = initiation.get();
            change.get();
            assertThat(result).isIn(200, 409);
            if (result == 200) {
                var row =
                        jdbc.queryForMap(
                                "select destination_id,method,phone_e164 from merchant_payouts where id=?",
                                id());
                assertThat(row.get("destination_id")).isEqualTo(destination);
                assertThat(row.get("method")).isEqualTo("WAVE");
                assertThat(row.get("phone_e164")).isEqualTo("+221771234567");
                assertThat(http.requests).hasSize(1);
                assertThat(
                                json.readTree(http.requests.getFirst().body())
                                        .path("customerObject")
                                        .path("phone")
                                        .asText())
                        .isEqualTo("+221771234567");
            } else assertThat(http.requests).isEmpty();
        }
        assertThat(debits()).isZero();
    }

    @Test
    void contradictoryLateAckLeavesSuccessImmutableAndCreatesIncident() throws Exception {
        var ack = evidence();
        ack.put("id", "different-ack-transaction");
        http.delayed(201, json.writeValueAsString(ack), 700);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(() -> request().andExpect(status().isOk()));
            await().atMost(Duration.ofSeconds(5)).until(() -> !http.requests.isEmpty());
            webhook(evidence());
            waitState("SUCCEEDED");
            pending.get();
        }
        assertThat(state()).isEqualTo("SUCCEEDED");
        assertThat(debits()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from refund_webhook_receipts where operation_id=? and last_error_code='PAYOUT_TRANSACTION_MISMATCH'",
                                Integer.class,
                                id()))
                .isPositive();
    }
}
