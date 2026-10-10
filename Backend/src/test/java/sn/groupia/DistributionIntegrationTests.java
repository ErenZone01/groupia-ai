package sn.groupia;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
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
    CustomerAccessIntegrationTests.DeliveryConfig.class
})
class DistributionIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired PasswordEncoder passwords;
    private static String hash;
    private UUID actor, org, product, variant;
    private String jwt;
    private int customerSequence;
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
        jdbc.execute(
                "TRUNCATE TABLE order_fulfillment_events, shipping_calculations, shipping_measurements, shipping_assessments, customer_access_sessions, otp_challenges, order_access_links, customer_access_limits, shipment_facts, shipments, supplier_order_anomalies, supplier_orders, payment_webhook_events, payments, payment_requests, order_items, orders, customers, grouping_changes, grouping_offers, grouping_campaigns, product_variants, products, organization_members, organizations, user_accounts, event_publication CASCADE");
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "3000,COMPLETED,SETTLED",
        "2500,CONFIRMED,CREDIT_DUE"
    })
    void readinessAndPickupKeepPhysicalAndCommercialStatesSeparate(
            int amount, String expected, String balance) throws Exception {
        finalizeAmount(amount).andExpect(status().isOk());
        long requests = requests();
        long confirmations =
                events.stream(sn.groupia.ordering.api.event.OrderCommerciallyAccepted.class)
                        .count();
        ready().andExpect(status().isOk())
                .andExpect(jsonPath("$.campaign.status").value("DISTRIBUTING"));
        assertThat(count("READY_FOR_PICKUP")).isEqualTo(1);
        var tracking =
                read(
                        mvc.perform(get(ACCESS + "/orders/" + orderNumber).cookie(cookie))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(tracking.path("readyForPickupAt").asText()).isNotBlank();
        assertThat(tracking.path("fulfillmentStatus").asText()).isEqualTo("READY_FOR_PICKUP");
        assertThat(tracking.toString())
                .doesNotContain("actorId", "history", "note", "customerName", "organizationId");
        pickup().andExpect(status().isOk());
        var row =
                jdbc.queryForMap(
                        "select status,fulfillment_status,ready_for_pickup_at,picked_up_at,completed_at from orders where id=?",
                        orderId());
        assertThat(row.get("status")).isEqualTo(expected);
        assertThat(row.get("fulfillment_status")).isEqualTo("PICKED_UP");
        assertThat(row.get("picked_up_at")).isNotNull();
        assertThat(row.get("ready_for_pickup_at")).isNotNull();
        assertThat(row.get("completed_at") != null).isEqualTo(expected.equals("COMPLETED"));
        assertThat(balance().path("status").asText()).isEqualTo(balance);
        assertThat(requests()).isEqualTo(requests);
        assertThat(
                        events.stream(sn.groupia.ordering.api.event.OrderCommerciallyAccepted.class)
                                .count())
                .isEqualTo(confirmations);
        assertThat(
                        jdbc.queryForObject(
                                "select status from grouping_campaigns where id=?",
                                String.class,
                                campaignId()))
                .isEqualTo("DISTRIBUTING");
        assertThat(
                        jdbc.queryForObject(
                                "select actor_id from order_fulfillment_events where type='PICKED_UP'",
                                UUID.class))
                .isEqualTo(actor);
        revoke("Erreur de tri").andExpect(status().isConflict());
        pickup().andExpect(status().isConflict());
        finalizeAmount(7000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_FULFILLMENT_LOCKED"));
    }

    @Test
    void requiresFinalShippingAndNoDebt() throws Exception {
        ready().andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FULFILLMENT_SHIPPING_NOT_FINALIZED"));
        finalizeAmount(8000).andExpect(status().isOk());
        ready().andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FULFILLMENT_SHIPPING_BALANCE_DUE"));
        assertThat(count("READY_FOR_PICKUP")).isZero();
        assertThat(batch().path("counts").path("paymentBlocked").asInt()).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void pendingOrUncertainPaymentBlocksPreparation(boolean uncertain) throws Exception {
        finalizeAmount(8000).andExpect(status().isOk());
        if (uncertain) http.disconnect();
        else queueCharge();
        postAccess(payPath(), Map.of("provider", "BICTORYS"), cookie, UUID.randomUUID().toString())
                .andExpect(status().isOk());
        ready().andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIPPING_BALANCE_PAYMENT_IN_PROGRESS"));
        assertThat(count("READY_FOR_PICKUP")).isZero();
    }

    @Test
    void inTransitCannotBeReady() throws Exception {
        finalizeAmount(3000).andExpect(status().isOk());
        jdbc.update("update grouping_campaigns set status='IN_TRANSIT' where id=?", campaignId());
        ready().andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FULFILLMENT_CAMPAIGN_NOT_ARRIVED"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"OWNER", "MANAGER", "OPERATOR"})
    void operationalRolesCanPrepareAndHandOver(String role) throws Exception {
        finalizeAmount(3000).andExpect(status().isOk());
        role(role);
        ready().andExpect(status().isOk());
        pickup().andExpect(status().isOk());
        if (role.equals("OPERATOR"))
            auth(post(base() + "/close"), Map.of("version", version()))
                    .andExpect(status().isForbidden());
    }

    @Test
    void viewerOnlyReadsAndInactiveOrganizationIsRejected() throws Exception {
        finalizeAmount(3000).andExpect(status().isOk());
        role("VIEWER");
        ready().andExpect(status().isForbidden());
        assertThat(batch().path("canManageFulfillment").asBoolean()).isFalse();
        role("OPERATOR");
        jdbc.update("update organizations set status='ARCHIVED' where id=?", org);
        ready().andExpect(status().isNotFound());
    }

    @Test
    void revokeRequiresReasonKeepsHistoryAndUnlocksCorrection() throws Exception {
        finalizeAmount(3000).andExpect(status().isOk());
        ready().andExpect(status().isOk());
        finalizeAmount(2500)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_FULFILLMENT_LOCKED"));
        auth(
                        post(base() + "/orders/" + orderId() + "/shipping-measurements"),
                        Map.of(
                                "version",
                                1,
                                "information",
                                Map.of("actualWeightKg", 2, "source", "FORWARDER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_FULFILLMENT_LOCKED"));
        revoke(" ").andExpect(status().isBadRequest());
        revoke("Article manquant").andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "select ready_for_pickup_at from orders where id=?",
                                java.sql.Timestamp.class,
                                orderId()))
                .isNull();
        assertThat(batch().path("campaign").path("status").asText()).isEqualTo("DISTRIBUTING");
        finalizeAmount(2500).andExpect(status().isOk());
        ready().andExpect(status().isOk());
        assertThat(count("READY_FOR_PICKUP")).isEqualTo(2);
        assertThat(count("READINESS_REVOKED")).isEqualTo(1);
        var types = new ArrayList<String>();
        batch().path("orders")
                .get(0)
                .path("history")
                .forEach(e -> types.add(e.path("type").asText()));
        assertThat(types)
                .containsExactly("READY_FOR_PICKUP", "READINESS_REVOKED", "READY_FOR_PICKUP");
    }

    @Test
    void distributionAllowsAnotherOrderToFinalizePayAndBecomeReady() throws Exception {
        finalizeAmount(3000).andExpect(status().isOk());
        ready().andExpect(status().isOk());
        orderNumber = secondOrder;
        auth(
                        post(base() + "/orders/" + orderId() + "/shipping-measurements"),
                        Map.of(
                                "version",
                                0,
                                "information",
                                Map.of("actualWeightKg", 2, "source", "FORWARDER")))
                .andExpect(status().isOk());
        finalizeAmount(8000).andExpect(status().isOk());
        // Existing ORDER_ACCESS belongs to the first order: exchange this second order's own link.
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
        var p = start();
        deliver(p.path("id").asText(), "succeeded");
        ready().andExpect(status().isOk());
        assertThat(count("READY_FOR_PICKUP")).isEqualTo(2);
    }

    @Test
    void explicitConfirmationAndAuthenticatedActorAreMandatory() throws Exception {
        finalizeAmount(3000).andExpect(status().isOk());
        auth(
                        post(actionPath("ready-for-pickup")),
                        Map.of("version", orderVersion(), "confirmed", false))
                .andExpect(status().isBadRequest());
        for (String key : List.of("actorId", "status", "organizationId", "financialStatus"))
            auth(
                            post(actionPath("ready-for-pickup")),
                            Map.of("version", orderVersion(), "confirmed", true, key, "forged"))
                    .andExpect(status().isBadRequest());
        mvc.perform(
                        post(actionPath("ready-for-pickup"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":1,\"confirmed\":true}"))
                .andExpect(status().isUnauthorized());
        ready().andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select actor_id from order_fulfillment_events", UUID.class))
                .isEqualTo(actor);
    }

    @Test
    void organizationAndCampaignIsolation() throws Exception {
        finalizeAmount(3000).andExpect(status().isOk());
        auth(
                        post(
                                actionPath("ready-for-pickup")
                                        .replace(org.toString(), UUID.randomUUID().toString())),
                        Map.of("version", orderVersion(), "confirmed", true))
                .andExpect(status().isForbidden());
        auth(
                        post(
                                actionPath("ready-for-pickup")
                                        .replace(
                                                campaignId().toString(),
                                                UUID.randomUUID().toString())),
                        Map.of("version", orderVersion(), "confirmed", true))
                .andExpect(status().isNotFound());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ready-for-pickup", "picked-up"})
    void concurrentAndStaleActionsCreateOneEvent(String action) throws Exception {
        finalizeAmount(3000).andExpect(status().isOk());
        if (action.equals("picked-up")) ready().andExpect(status().isOk());
        var body = Map.of("version", orderVersion(), "confirmed", true);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> call =
                    () ->
                            auth(post(actionPath(action)), body)
                                    .andReturn()
                                    .getResponse()
                                    .getStatus();
            var a = pool.submit(call);
            var b = pool.submit(call);
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
        }
        auth(post(actionPath(action)), body).andExpect(status().isConflict());
        assertThat(count(action.equals("picked-up") ? "PICKED_UP" : "READY_FOR_PICKUP"))
                .isEqualTo(1);
    }

    @Test
    void failedEventInsertRollsBackOrderAndCampaign() throws Exception {
        finalizeAmount(3000).andExpect(status().isOk());
        jdbc.execute(
                "alter table order_fulfillment_events add constraint fixture_reject_ready check(type<>'READY_FOR_PICKUP')");
        try {
            ready().andExpect(status().isInternalServerError());
        } finally {
            jdbc.execute(
                    "alter table order_fulfillment_events drop constraint fixture_reject_ready");
        }
        assertThat(
                        jdbc.queryForObject(
                                "select fulfillment_status from orders where id=?",
                                String.class,
                                orderId()))
                .isEqualTo("NOT_READY");
        assertThat(
                        jdbc.queryForObject(
                                "select status from grouping_campaigns where id=?",
                                String.class,
                                campaignId()))
                .isEqualTo("ARRIVED");
        assertThat(count("READY_FOR_PICKUP")).isZero();
    }

    @Test
    void historyCannotBeEditedOrDeleted() throws Exception {
        finalizeAmount(3000).andExpect(status().isOk());
        ready().andExpect(status().isOk());
        assertThatThrownBy(() -> jdbc.update("update order_fulfillment_events set note='changed'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from order_fulfillment_events"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void concurrentCorrectionAndPreparationCannotMakeADebtorReady() throws Exception {
        finalizeAmount(3000).andExpect(status().isOk());
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var ready = pool.submit(() -> ready().andReturn().getResponse().getStatus());
            var correction =
                    pool.submit(() -> finalizeAmount(8000).andReturn().getResponse().getStatus());
            assertThat(
                            List.of(
                                    ready.get(20, java.util.concurrent.TimeUnit.SECONDS),
                                    correction.get(20, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        var state =
                jdbc.queryForObject(
                        "select fulfillment_status from orders where id=?",
                        String.class,
                        orderId());
        if (state.equals("READY_FOR_PICKUP")) {
            assertThat(balance().path("status").asText()).isEqualTo("SETTLED");
            assertThat(count("READY_FOR_PICKUP")).isEqualTo(1);
        } else {
            assertThat(balance().path("status").asText()).isEqualTo("BALANCE_DUE");
            assertThat(count("READY_FOR_PICKUP")).isZero();
        }
    }

    @Autowired sn.groupia.ordering.application.service.OrderSettlementListener settlementListener;

    @Test
    void delayedShippingSettlementConsumerAcceptsCompletedOrder() throws Exception {
        finalizeAmount(8000).andExpect(status().isOk());
        var p = start();
        deliver(p.path("id").asText(), "succeeded");
        ready().andExpect(status().isOk());
        pickup().andExpect(status().isOk());
        var event =
                events.stream(sn.groupia.payment.api.event.PaymentRequestSettled.class)
                        .filter(
                                e ->
                                        e.purpose()
                                                == sn.groupia.payment.api.PaymentPurpose
                                                        .SHIPPING_BALANCE)
                        .findFirst()
                        .orElseThrow();
        settlementListener.on(event);
        settlementListener.on(event);
        assertThat(count("PICKED_UP")).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select status from orders where id=?", String.class, orderId()))
                .isEqualTo("COMPLETED");
    }

    @Test
    void pickupRevalidatesFinanceEvenAfterPreparation() throws Exception {
        finalizeAmount(3000).andExpect(status().isOk());
        ready().andExpect(status().isOk());
        var request =
                new org.springframework.transaction.support.TransactionTemplate(tx)
                        .execute(
                                t ->
                                        payments.createRequest(
                                                new sn.groupia.payment.api.dto.CreatePaymentRequest(
                                                        org,
                                                        orderId(),
                                                        sn.groupia.payment.api.PaymentPurpose
                                                                .SHIPPING_BALANCE,
                                                        "fixture-uncertain",
                                                        java.math.BigDecimal.ONE,
                                                        "XOF",
                                                        java.math.BigDecimal.ZERO,
                                                        java.math.BigDecimal.ZERO,
                                                        java.math.BigDecimal.ONE,
                                                        null)));
        payments.initiateRequest(
                org, request.id(), "SIMULATED", UUID.randomUUID().toString(), null);
        pickup().andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIPPING_BALANCE_PAYMENT_IN_PROGRESS"));
        assertThat(count("PICKED_UP")).isZero();
    }

    private UUID campaignId() {
        return UUID.fromString(campaign.path("id").asText());
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
