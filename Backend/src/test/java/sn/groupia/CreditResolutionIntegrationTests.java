package sn.groupia;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        properties = {
            "groupia.customer-access.enabled=true",
            "groupia.customer-access.public-base-url=https://groupia.test"
        })
@org.springframework.test.context.event.RecordApplicationEvents
@AutoConfigureMockMvc
@Import({
    TestcontainersConfiguration.class,
    PaymentTestConfiguration.class,
    sn.groupia.payment.infrastructure.provider.bictorys.BictorysTestServerConfiguration.class,
    CustomerAccessIntegrationTests.DeliveryConfig.class,
    sn.groupia.payment.infrastructure.provider.bictorys.PayoutTestConfiguration.class
})
class CreditResolutionIntegrationTests {
    private UUID campaignId() {
        return UUID.fromString(campaign.path("id").asText());
    }

    @Autowired
    sn.groupia.payment.infrastructure.provider.bictorys.PayoutTestConfiguration.Harness payout;

    @Autowired sn.groupia.payment.application.service.RefundTransactions refunds;
    @Autowired sn.groupia.ordering.application.service.OrderRefundListener refundListener;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired PasswordEncoder passwords;
    private static String hash;
    private UUID actor, org, product, variant;
    private String jwt;
    private int customerSequence;
    private static final java.util.concurrent.atomic.AtomicInteger addresses =
            new java.util.concurrent.atomic.AtomicInteger();
    private String remote;
    private String secondOrder;
    private JsonNode campaign;

    @BeforeEach
    void setup() throws Exception {
        await().atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "select count(*) from event_publication where status in ('PUBLISHED','PROCESSING','RESUBMITTED')",
                                                        Long.class))
                                        .isZero());
        TestEventDrain.awaitIdle(jdbc);
        jdbc.execute(
                "TRUNCATE TABLE refund_webhook_receipts, payment_refunds, order_fulfillment_events, shipping_calculations, shipping_measurements, shipping_assessments, customer_access_sessions, otp_challenges, order_access_links, customer_access_limits, shipment_facts, shipments, supplier_order_anomalies, supplier_orders, payment_webhook_events, payments, payment_requests, order_items, orders, customers, grouping_changes, grouping_offers, grouping_campaigns, product_variants, products, organization_members, organizations, user_accounts, event_publication CASCADE");
        remote = "192.0.2." + addresses.incrementAndGet();
        payout.available = true;
        http.reset();
        clock.reset();
        delivery.links.clear();
        delivery.codes.clear();
        if (hash == null) hash = passwords.encode("Supplier fixture 2026!");
        actor = UUID.randomUUID();
        jdbc.update(
                "insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at) values (?,'+221771234567',?,'ACTIVE',current_timestamp,current_timestamp)",
                actor,
                hash);
        jwt = token(actor);
        org =
                UUID.fromString(
                        read(auth(post("/api/organizations"), Map.of("name", "Boutique"))
                                        .andExpect(status().isCreated())
                                        .andReturn())
                                .path("id")
                                .asText());
        var p = product(org, Map.of("color", "Noir", "size", "M"));
        product = UUID.fromString(p.path("id").asText());
        variant = UUID.fromString(p.path("variants").get(0).path("id").asText());
        campaign = createCampaign(org, variant);
        orderNumber = order(campaign, offer(), 1, 5000, 0, "CONFIRMED");
        secondOrder = order(campaign, offer(), 1, 5000, 0, "CONFIRMED");
        close();
        campaign =
                read(freeze(version()).andExpect(status().isCreated()).andReturn())
                        .path("campaign");
        var r = mvc.perform(get(ACCESS + "/csrf")).andReturn();
        csrf = read(r).path("token").asText();
        csrfCookie = r.getResponse().getCookie("__Host-groupia_csrf");
        access.confirmed(org, orderId());
        var link =
                crypto.link(
                        jdbc.queryForObject(
                                "select id from order_access_links where order_id=? and revoked_at is null",
                                UUID.class,
                                orderId()));
        cookie =
                postAccess("/magic/exchange", Map.of("token", link), null, null)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getCookie("__Host-groupia_customer");
        http.reset();
        campaign =
                read(auth(post(base() + "/ship"), Map.of("version", version()))
                                .andExpect(status().isOk())
                                .andReturn())
                        .path("campaign");
        campaign =
                read(auth(post(base() + "/arrive"), Map.of("version", version()))
                                .andExpect(status().isOk())
                                .andReturn())
                        .path("campaign");
    }

    @Autowired sn.groupia.payment.infrastructure.provider.bictorys.BictorysHttpStub http;
    @Autowired PaymentTestConfiguration.MutablePaymentClock clock;
    @Autowired sn.groupia.payment.api.PaymentOperations payments;
    @Autowired sn.groupia.payment.application.service.PaymentService paymentService;
    @Autowired sn.groupia.customeraccess.application.service.CustomerAccess access;
    @Autowired sn.groupia.customeraccess.application.service.AccessCrypto crypto;
    @Autowired CustomerAccessIntegrationTests.FakeDelivery delivery;
    @Autowired org.springframework.test.context.event.ApplicationEvents events;
    @Autowired org.springframework.transaction.PlatformTransactionManager tx;
    @Autowired PaymentTestConfiguration.SimulatedProvider provider;
    private static final String ACCESS = "/api/public/customer-access";
    private String trackingToken, orderNumber, csrf;
    private jakarta.servlet.http.Cookie cookie, csrfCookie;

    private UUID orderId() {
        return jdbc.queryForObject(
                "select id from orders where order_number=?", UUID.class, orderNumber);
    }

    private String base() {
        return "/api/organizations/" + org + "/grouping-campaigns/" + campaignId();
    }

    private String finalPath() {
        return base() + "/orders/" + orderId() + "/shipping-finalizations";
    }

    private String payPath() {
        return "/orders/" + orderNumber + "/shipping-balance/payments";
    }

    private ResultActions finalizeAmount(int amount) throws Exception {
        long version =
                jdbc.queryForObject(
                        "select coalesce(max(version),0) from shipping_assessments where order_id=?",
                        Long.class,
                        orderId());
        return auth(
                post(finalPath()),
                Map.of(
                        "version",
                        version,
                        "origin",
                        "PROVIDER_QUOTED",
                        "quotedAmount",
                        amount,
                        "currency",
                        "XOF",
                        "source",
                        "FORWARDER",
                        "correctionReason",
                        "Montant corrigé"));
    }

    private JsonNode balance() throws Exception {
        return read(mvc.perform(get(ACCESS + "/orders/" + orderNumber).cookie(cookie))
                        .andExpect(status().isOk())
                        .andReturn())
                .path("shippingSettlement");
    }

    private UUID requestId() {
        return jdbc.queryForObject(
                "select id from payment_requests where purpose='SHIPPING_BALANCE' and acceptance_status='OPEN'",
                UUID.class);
    }

    private long requests() {
        return jdbc.queryForObject(
                "select count(*) from payment_requests where purpose='SHIPPING_BALANCE'",
                Long.class);
    }

    private void queueCharge() {
        http.reply(
                202,
                "{\"type\":\"CheckoutLinkObject\",\"chargeId\":\""
                        + UUID.randomUUID()
                        + "\",\"link\":\"https://pay.bictorys.com/checkout\"}");
    }

    private ResultActions postAccess(
            String path, Object body, jakarta.servlet.http.Cookie session, String key)
            throws Exception {
        var r =
                post(ACCESS + path)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body));
        if (session != null) r.cookie(session);
        if (key != null) r.header("Idempotency-Key", key);
        return mvc.perform(r);
    }

    private JsonNode start() throws Exception {
        queueCharge();
        return read(
                postAccess(
                                payPath(),
                                Map.of("provider", "BICTORYS"),
                                cookie,
                                UUID.randomUUID().toString())
                        .andExpect(status().isOk())
                        .andReturn());
    }

    private void deliver(String paymentId, String state) throws Exception {
        var p =
                jdbc.queryForMap(
                        "select merchant_reference,payment_reference,expected_amount from payments where id=?",
                        UUID.fromString(paymentId));
        mvc.perform(
                        post("/api/payment/webhooks/bictorys")
                                .header("X-Secret-Key", "fixture-webhook-secret")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "id",
                                                        "tx-" + paymentId,
                                                        "merchantReference",
                                                        p.get("merchant_reference"),
                                                        "paymentReference",
                                                        p.get("payment_reference"),
                                                        "amount",
                                                        p.get("expected_amount"),
                                                        "currency",
                                                        "XOF",
                                                        "status",
                                                        state,
                                                        "timestamp",
                                                        clock.instant().toString()))))
                .andExpect(status().isAccepted());
    }

    private long orderVersion() {
        return jdbc.queryForObject("select version from orders where id=?", Long.class, orderId());
    }

    private String actionPath(String action) {
        return base() + "/orders/" + orderId() + "/" + action;
    }

    private ResultActions ready() throws Exception {
        return auth(
                post(actionPath("ready-for-pickup")),
                Map.of("version", orderVersion(), "confirmed", true));
    }

    private ResultActions pickup() throws Exception {
        return auth(
                post(actionPath("picked-up")),
                Map.of("version", orderVersion(), "confirmed", true));
    }

    private ResultActions revoke(String reason) throws Exception {
        return auth(
                post(actionPath("revoke-readiness")),
                Map.of("version", orderVersion(), "reason", reason));
    }

    private JsonNode batch() throws Exception {
        return read(auth(get(base() + "/fulfillment")).andExpect(status().isOk()).andReturn());
    }

    private long count(String type) {
        return jdbc.queryForObject(
                "select count(*) from order_fulfillment_events where type=?", Long.class, type);
    }

    private void role(String role) {
        jdbc.update(
                "update organization_members set role=? where organization_id=? and user_account_id=?",
                role,
                org,
                actor);
    }

    private String creditPath() {
        return "/api/organizations/" + org + "/orders/" + orderId() + "/shipping-credit";
    }

    private Map<String, Object> manual(String method) {
        return new HashMap<>(
                Map.of(
                        "version",
                        orderVersion(),
                        "method",
                        "MANUAL_EXTERNAL",
                        "externalMethod",
                        method,
                        "confirmed",
                        true,
                        "reference",
                        "external-fixture-123",
                        "note",
                        "Restitution confirmée"));
    }

    private Map<String, Object> automated() {
        return new HashMap<>(
                Map.of(
                        "version",
                        orderVersion(),
                        "method",
                        "BICTORYS_PAYOUT",
                        "externalMethod",
                        "WAVE",
                        "confirmed",
                        true));
    }

    private void stable() throws Exception {
        finalizeAmount(2500).andExpect(status().isOk());
        ready().andExpect(status().isOk());
        pickup().andExpect(status().isOk());
    }

    private UUID refundId() {
        return jdbc.queryForObject(
                "select id from payment_refunds where order_id=?", UUID.class, orderId());
    }

    private String refundState() {
        return jdbc.queryForObject(
                "select status from payment_refunds where id=?", String.class, refundId());
    }

    private JsonNode credit() throws Exception {
        return read(auth(get(creditPath())).andExpect(status().isOk()).andReturn());
    }

    private Map<String, Object> evidence(String status) {
        var r = jdbc.queryForMap("select * from payment_refunds where id=?", refundId());
        return new HashMap<>(
                Map.of(
                        "id",
                        "refund-tx",
                        "merchantReference",
                        r.get("merchant_reference"),
                        "transactionType",
                        "refund",
                        "status",
                        status,
                        "amount",
                        -500,
                        "currency",
                        "XOF",
                        "customerPhone",
                        r.get("recipient_phone_snapshot"),
                        "timestamp",
                        clock.instant().toString()));
    }

    private ResultActions webhook(Map<String, Object> body, String secret) throws Exception {
        return mvc.perform(
                post("/api/payment/webhooks/bictorys")
                        .header("X-Secret-Key", secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)));
    }

    private void startPayout() throws Exception {
        http.reply(201, "{\"id\":\"refund-tx\",\"status\":0}");
        auth(post(creditPath()), automated()).andExpect(status().isOk());
    }

    private void completed() {
        await().atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "select status from orders where id=?",
                                                        String.class,
                                                        orderId()))
                                        .isEqualTo("COMPLETED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_READY", "READY_FOR_PICKUP"})
    void refundRequiresPickup(String physical) throws Exception {
        finalizeAmount(2500).andExpect(status().isOk());
        if (physical.equals("READY_FOR_PICKUP")) ready().andExpect(status().isOk());
        auth(post(creditPath()), manual("WAVE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CREDIT_NOT_STABLE_FOR_REFUND"));
        assertThat(jdbc.queryForObject("select count(*) from payment_refunds", Integer.class))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "MANAGER"})
    void manualRefundSettlesAndCompletesOnlyTheOrder(String role) throws Exception {
        stable();
        role(role);
        var input = manual("WAVE");
        var original = jdbc.queryForList("select * from payments order by id");
        var requests = jdbc.queryForList("select * from payment_requests order by id");
        var picked =
                jdbc.queryForObject(
                        "select picked_up_at from orders where id=?",
                        java.sql.Timestamp.class,
                        orderId());
        long accepted =
                events.stream(sn.groupia.ordering.api.event.OrderCommerciallyAccepted.class)
                        .count();
        auth(post(creditPath()), input)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlement.status").value("SETTLED"))
                .andExpect(jsonPath("$.settlement.netShippingPaid").value(2500))
                .andExpect(jsonPath("$.settlement.successfulShippingCreditRefunds").value(500));
        completed();
        auth(post(creditPath()), input).andExpect(status().isOk());
        assertThat(jdbc.queryForList("select * from payments order by id")).isEqualTo(original);
        assertThat(jdbc.queryForList("select * from payment_requests order by id"))
                .isEqualTo(requests);
        assertThat(
                        jdbc.queryForObject(
                                "select picked_up_at from orders where id=?",
                                java.sql.Timestamp.class,
                                orderId()))
                .isEqualTo(picked);
        assertThat(
                        events.stream(sn.groupia.ordering.api.event.OrderCommerciallyAccepted.class)
                                .count())
                .isEqualTo(accepted);
        assertThat(
                        jdbc.queryForObject(
                                "select status from grouping_campaigns where id=?",
                                String.class,
                                campaignId()))
                .isEqualTo("DISTRIBUTING");
        assertThat(jdbc.queryForObject("select initiated_by from payment_refunds", UUID.class))
                .isEqualTo(actor);
        var publicBalance = balance();
        assertThat(publicBalance.path("refunds").get(0).path("method").asText())
                .isEqualTo("MANUAL_EXTERNAL");
        assertThat(publicBalance.toString())
                .doesNotContain(
                        "initiatedBy",
                        "actorId",
                        "manualReference",
                        "external-fixture",
                        "note",
                        "merchantReference",
                        "recipientPhone",
                        "providerIdempotencyKey");
        assertThat(jdbc.queryForObject("select count(*) from payment_refunds", Integer.class))
                .isEqualTo(1);
        assertThat(http.requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPERATOR", "VIEWER"})
    void operationalPermissionCannotSendMoney(String role) throws Exception {
        stable();
        role(role);
        auth(post(creditPath()), manual("CASH")).andExpect(status().isForbidden());
        assertThat(batch().path("canManageRefunds").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from payment_refunds", Integer.class))
                .isZero();
    }

    @Test
    void settledOrderCannotBeRefunded() throws Exception {
        finalizeAmount(3000).andExpect(status().isOk());
        ready().andExpect(status().isOk());
        pickup().andExpect(status().isOk());
        auth(post(creditPath()), manual("WAVE"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIPPING_CREDIT_NOT_DUE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAVE", "ORANGE_MONEY", "BANK_TRANSFER", "OTHER", "CASH"})
    void manualEvidenceIsMandatory(String method) throws Exception {
        stable();
        var input = manual(method);
        input.remove(method.equals("CASH") ? "note" : "reference");
        auth(post(creditPath()), input)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REFUND_EVIDENCE_REQUIRED"));
        input = manual(method);
        if (method.equals("CASH")) input.remove("reference");
        auth(post(creditPath()), input).andExpect(status().isOk());
        completed();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "amount",
                "currency",
                "organizationId",
                "orderId",
                "paymentId",
                "recipientPhone",
                "actorId"
            })
    void browserCannotOverrideFinancialOrRecipientFields(String field) throws Exception {
        stable();
        var input = manual("WAVE");
        input.put(field, field.equals("amount") ? 200 : "attacker-value");
        auth(post(creditPath()), input).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from payment_refunds", Integer.class))
                .isZero();
    }

    @Test
    void payoutInitiationIsPendingAndServerOwned() throws Exception {
        stable();
        startPayout();
        assertThat(refundState()).isEqualTo("PENDING");
        assertThat(balance().path("creditAmount").asInt()).isEqualTo(500);
        var request = http.requests.getFirst();
        var body = json.readTree(request.body());
        assertThat(request.path()).isEqualTo("/pay/v1/payouts?payment_type=wave_money");
        assertThat(request.apiKey()).isEqualTo("test_secret-fixture");
        assertThat(request.idempotencyKey())
                .isEqualTo(
                        jdbc.queryForObject(
                                        "select provider_idempotency_key from payment_refunds",
                                        UUID.class)
                                .toString());
        assertThat(body.path("merchant").path("secretCode").asText())
                .isEqualTo("fixture-merchant-code");
        assertThat(body.path("transactionType").asText()).isEqualTo("refund");
        assertThat(body.path("amount").asInt()).isEqualTo(500);
        assertThat(body.path("currency").asText()).isEqualTo("XOF");
        assertThat(body.path("customerObject").path("phone").asText()).isEqualTo("+221770000001");
        auth(post(creditPath()), automated()).andExpect(status().isOk());
        assertThat(http.requests).hasSize(1);
    }

    @Test
    void validPayoutWebhookSettlesExactlyOnceAndCannotRegress() throws Exception {
        stable();
        startPayout();
        var original = jdbc.queryForList("select * from payments order by id");
        var e = evidence("succeeded");
        webhook(e, "fixture-webhook-secret").andExpect(status().isAccepted());
        completed();
        var facts = jdbc.queryForMap("select * from payment_refunds where id=?", refundId());
        webhook(e, "fixture-webhook-secret").andExpect(status().isAccepted());
        webhook(evidence("pending"), "fixture-webhook-secret").andExpect(status().isAccepted());
        assertThat(jdbc.queryForMap("select * from payment_refunds where id=?", refundId()))
                .isEqualTo(facts);
        assertThat(balance().path("status").asText()).isEqualTo("SETTLED");
        assertThat(
                        events.stream(
                                        sn.groupia.payment.api.event.ShippingCreditRefundSettled
                                                .class)
                                .count())
                .isEqualTo(1);
        assertThat(jdbc.queryForList("select * from payments order by id")).isEqualTo(original);
        refundListener.on(
                events.stream(sn.groupia.payment.api.event.ShippingCreditRefundSettled.class)
                        .findFirst()
                        .orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "amount",
                "currency",
                "customerPhone",
                "id",
                "merchantReference",
                "transactionType"
            })
    void inconsistentWebhookNeverSettles(String field) throws Exception {
        stable();
        startPayout();
        var e = evidence("succeeded");
        e.put(
                field,
                switch (field) {
                    case "amount" -> 501;
                    case "currency" -> "EUR";
                    case "customerPhone" -> "+221770000009";
                    case "id" -> "other-tx";
                    case "transactionType" -> "payment";
                    default -> "GPR-unknown";
                });
        webhook(e, "fixture-webhook-secret").andExpect(status().isAccepted());
        assertThat(refundState()).isNotEqualTo("SUCCEEDED");
        assertThat(balance().path("status").asText()).isEqualTo("CREDIT_DUE");
        assertThat(
                        jdbc.queryForObject(
                                "select processing_status from refund_webhook_receipts",
                                String.class))
                .isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    void missingTransactionAndBadSecretNeverSettle() throws Exception {
        stable();
        startPayout();
        var e = evidence("succeeded");
        webhook(e, "wrong-secret").andExpect(status().isUnauthorized());
        e.remove("id");
        webhook(e, "fixture-webhook-secret").andExpect(status().isAccepted());
        assertThat(refundState()).isEqualTo("PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "select last_error_code from refund_webhook_receipts where not authentic",
                                String.class))
                .isEqualTo("PAYMENT_AUTHENTICITY_FAILED");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500, 503})
    void providerHttpErrorIsUncertainAndDoesNotAllowSecondSend(int status) throws Exception {
        stable();
        http.reply(status, "{\"error\":\"synthetic\"}");
        auth(post(creditPath()), automated()).andExpect(status().isOk());
        assertThat(refundState()).isEqualTo("REVIEW_REQUIRED");
        auth(post(creditPath()), automated()).andExpect(status().isOk());
        assertThat(http.requests).hasSize(1);
        assertThat(balance().path("creditAmount").asInt()).isEqualTo(500);
        auth(post(creditPath()), manual("CASH")).andExpect(status().isConflict());
    }

    @Test
    void timeoutRetainsCreditAndLateWebhookCanSettle() throws Exception {
        stable();
        http.delayed(201, "{\"id\":\"refund-tx\"}", 1500);
        auth(post(creditPath()), automated()).andExpect(status().isOk());
        assertThat(refundState()).isEqualTo("REVIEW_REQUIRED");
        auth(post(creditPath()), automated()).andExpect(status().isOk());
        assertThat(http.requests).hasSize(1);
        webhook(evidence("succeeded"), "fixture-webhook-secret").andExpect(status().isAccepted());
        completed();
    }

    @Test
    void manualAndAutomatedIsolationAndDisabledProvider() throws Exception {
        stable();
        payout.available = false;
        auth(post(creditPath()), automated()).andExpect(status().isServiceUnavailable());
        assertThat(credit().path("payoutsEnabled").asBoolean()).isFalse();
        auth(
                        post(creditPath().replace(org.toString(), UUID.randomUUID().toString())),
                        manual("CASH"))
                .andExpect(status().isForbidden());
        jdbc.update("update organizations set status='ARCHIVED' where id=?", org);
        auth(post(creditPath()), manual("CASH")).andExpect(status().isNotFound());
    }

    @Test
    void reconciliationUsesStrongProofAndNeverSendsAnotherPayout() throws Exception {
        stable();
        startPayout();
        String path = "/api/organizations/" + org + "/refunds/" + refundId() + "/reconcile";
        http.reply(200, "{\"id\":null,\"status\":\"pending\"}");
        auth(post(path)).andExpect(status().isOk());
        assertThat(refundState()).isEqualTo("REVIEW_REQUIRED");
        auth(get("/api/organizations/" + org + "/refunds/review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].refund.reconciliationRequired").value(true));
        http.reply(200, json.writeValueAsString(evidence("succeeded")));
        auth(post(path)).andExpect(status().isOk());
        completed();
        assertThat(http.requests.stream().filter(r -> r.method().equals("POST")).count())
                .isEqualTo(1);
    }

    @Test
    void concurrentClicksSendOnlyOnePayout() throws Exception {
        stable();
        http.delayed(201, "{\"id\":\"refund-tx\"}", 300);
        var body = automated();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () ->
                                    auth(post(creditPath()), body)
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            var b =
                    pool.submit(
                            () ->
                                    auth(post(creditPath()), body)
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            assertThat(a.get()).isEqualTo(200);
            assertThat(b.get()).isEqualTo(200);
        }
        assertThat(http.requests).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from payment_refunds", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void successfulRefundFactsAndRecipientCannotBeRewritten() throws Exception {
        stable();
        startPayout();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update payment_refunds set recipient_phone_snapshot='+221770000009'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        webhook(evidence("succeeded"), "fixture-webhook-secret").andExpect(status().isAccepted());
        completed();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update payment_refunds set status='FAILED',completed_at=null"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from payment_refunds"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "CANCELLED"})
    void finalFailureKeepsCreditAndLateSuccessIsNotLost(String state) throws Exception {
        stable();
        startPayout();
        webhook(evidence(state.toLowerCase()), "fixture-webhook-secret")
                .andExpect(status().isAccepted());
        assertThat(refundState()).isEqualTo(state);
        assertThat(balance().path("creditAmount").asInt()).isEqualTo(500);
        webhook(evidence("succeeded"), "fixture-webhook-secret").andExpect(status().isAccepted());
        completed();
    }

    @Test
    void transactionAlreadyAssignedToIncomingPaymentCannotSettleRefund() throws Exception {
        stable();
        String existing =
                jdbc.queryForObject(
                        "select provider_transaction_id from payments order by id limit 1",
                        String.class);
        http.reply(201, json.writeValueAsString(Map.of("id", existing)));
        auth(post(creditPath()), automated()).andExpect(status().isOk());
        assertThat(refundState()).isEqualTo("REVIEW_REQUIRED");
        var e = evidence("succeeded");
        e.put("id", existing);
        webhook(e, "fixture-webhook-secret").andExpect(status().isAccepted());
        assertThat(
                        jdbc.queryForObject(
                                "select last_error_code from refund_webhook_receipts",
                                String.class))
                .isEqualTo("PAYOUT_TRANSACTION_ALREADY_USED");
        assertThat(balance().path("status").asText()).isEqualTo("CREDIT_DUE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "account"})
    void providerAndAccountMustMatchTheReservation(String mismatch) throws Exception {
        stable();
        startPayout();
        var r = jdbc.queryForMap("select * from payment_refunds where id=?", refundId());
        var observation =
                new sn.groupia.payment.domain.service.RefundObservation(
                        mismatch.equals("provider") ? "OTHER" : "BICTORYS",
                        mismatch.equals("account") ? "other-account" : "sandbox",
                        r.get("merchant_reference").toString(),
                        "refund-tx",
                        new java.math.BigDecimal("500"),
                        "XOF",
                        r.get("recipient_phone_snapshot").toString(),
                        "SUCCEEDED",
                        clock.instant());
        refunds.apply(null, refundId(), observation);
        assertThat(refundState()).isEqualTo("REVIEW_REQUIRED");
        assertThat(
                        jdbc.queryForObject(
                                "select reconciliation_reason from payment_refunds", String.class))
                .isEqualTo("PAYOUT_PROVIDER_MISMATCH");
    }

    @Test
    void ambiguousSuccessfulHttpResponseRemainsUncertain() throws Exception {
        stable();
        http.reply(202, "{\"status\":0}");
        auth(post(creditPath()), automated()).andExpect(status().isOk());
        assertThat(refundState()).isEqualTo("REVIEW_REQUIRED");
        assertThat(balance().path("status").asText()).isEqualTo("CREDIT_DUE");
    }

    @Test
    void orangeMoneyUsesWhitelistedProviderMethodAndConfirmationIsMandatory() throws Exception {
        stable();
        var input = automated();
        input.put("confirmed", false);
        auth(post(creditPath()), input).andExpect(status().isBadRequest());
        input.put("confirmed", true);
        input.put("externalMethod", "ORANGE_MONEY");
        http.reply(201, "{\"id\":\"orange-refund\"}");
        auth(post(creditPath()), input).andExpect(status().isOk());
        assertThat(http.requests.getFirst().path()).endsWith("payment_type=orange_money");
    }

    private long version() {
        return campaign.path("version").asLong();
    }

    private void close() throws Exception {
        campaign =
                read(
                        auth(
                                        post(
                                                "/api/organizations/"
                                                        + org
                                                        + "/grouping-campaigns/"
                                                        + campaignId()
                                                        + "/close"),
                                        Map.of("version", version()))
                                .andExpect(status().isOk())
                                .andReturn());
    }

    private String purchasePath(UUID owner) {
        return "/api/organizations/"
                + owner
                + "/grouping-campaigns/"
                + campaignId()
                + "/supplier-order";
    }

    private ResultActions freeze(long version) throws Exception {
        return auth(post(purchasePath(org)), Map.of("campaignVersion", version));
    }

    private ResultActions frozen() throws Exception {
        return auth(get(purchasePath(org)));
    }

    private JsonNode product(UUID owner, Map<String, String> attrs) throws Exception {
        return read(
                auth(
                                post("/api/organizations/" + owner + "/products"),
                                Map.of(
                                        "name",
                                        "Chemise",
                                        "description",
                                        "Description historique",
                                        "variants",
                                        List.of(
                                                Map.of(
                                                        "attributes",
                                                        attrs,
                                                        "sku",
                                                        "SKU-" + UUID.randomUUID(),
                                                        "saleUnit",
                                                        "UNIT"))))
                        .andExpect(status().isCreated())
                        .andReturn());
    }

    private JsonNode createCampaign(UUID owner, UUID variantId) throws Exception {
        String base = "/api/organizations/" + owner + "/grouping-campaigns";
        var c =
                read(
                        auth(
                                        post(base),
                                        Map.of(
                                                "title",
                                                "Groupage",
                                                "shippingTerms",
                                                Map.of(
                                                        "transportMode",
                                                        "AIR",
                                                        "shippingPricingMethod",
                                                        "FIXED",
                                                        "shippingCollectionMode",
                                                        "ESTIMATED_AT_CHECKOUT",
                                                        "shippingRate",
                                                        3000)))
                                .andExpect(status().isCreated())
                                .andReturn());
        c =
                read(
                        auth(
                                        post(base + "/" + c.path("id").asText() + "/offers"),
                                        Map.of(
                                                "campaignVersion",
                                                c.path("version").asLong(),
                                                "productVariantId",
                                                variantId,
                                                "unitPrice",
                                                5000,
                                                "commercialLabel",
                                                "Même libellé"))
                                .andExpect(status().isOk())
                                .andReturn());
        return read(
                auth(
                                post(base + "/" + c.path("id").asText() + "/open"),
                                Map.of("version", c.path("version").asLong()))
                        .andExpect(status().isOk())
                        .andReturn());
    }

    private String order(
            JsonNode c, String offer, int quantity, int price, long revision, String state)
            throws Exception {
        var response =
                read(
                        mvc.perform(
                                        post("/api/public/groupings/"
                                                        + c.path("publicIdentifier").asText()
                                                        + "/checkout")
                                                .header(
                                                        "Idempotency-Key",
                                                        UUID.randomUUID().toString())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "contact",
                                                                        Map.of(
                                                                                "firstName",
                                                                                "Awa",
                                                                                "lastName",
                                                                                "Diop",
                                                                                "phone",
                                                                                "+22177"
                                                                                        + String
                                                                                                .format(
                                                                                                        "%07d",
                                                                                                        ++customerSequence)),
                                                                        "lines",
                                                                        List.of(
                                                                                Map.of(
                                                                                        "offerId",
                                                                                        offer,
                                                                                        "quantity",
                                                                                        quantity,
                                                                                        "expectedUnitPrice",
                                                                                        price,
                                                                                        "expectedPriceRevision",
                                                                                        revision))))))
                                .andExpect(status().isCreated())
                                .andReturn());
        trackingToken = response.path("trackingToken").asText();
        String number = response.path("orderNumber").asText();
        queueCharge();
        var p =
                read(
                        mvc.perform(
                                        post("/api/public/orders/" + number + "/payments")
                                                .with(
                                                        r -> {
                                                            r.setRemoteAddr(remote);
                                                            return r;
                                                        })
                                                .header("X-Order-Tracking-Token", trackingToken)
                                                .header(
                                                        "Idempotency-Key",
                                                        UUID.randomUUID().toString())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"provider\":\"BICTORYS\"}"))
                                .andExpect(status().isOk())
                                .andReturn());
        deliver(p.path("id").asText(), "succeeded");
        await().atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "select status from orders where order_number=?",
                                                        String.class,
                                                        number))
                                        .isEqualTo("CONFIRMED"));
        return number;
    }

    private String offer() {
        return campaign.path("offers").get(0).path("id").asText();
    }

    private ResultActions auth(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + jwt));
    }

    private ResultActions auth(MockHttpServletRequestBuilder request, Object body)
            throws Exception {
        return auth(
                request.contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)));
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String token(UUID id) {
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(),
                                JwtClaimsSet.builder()
                                        .issuer("https://groupia.test")
                                        .audience(List.of("groupia-api-test"))
                                        .subject(id.toString())
                                        .issuedAt(Instant.now())
                                        .expiresAt(Instant.now().plusSeconds(1800))
                                        .build()))
                .getTokenValue();
    }
}
