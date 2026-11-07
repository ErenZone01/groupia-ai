package sn.groupia;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.event.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.application.service.*;
import sn.groupia.payment.domain.service.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import({
    TestcontainersConfiguration.class,
    PaymentTestConfiguration.class,
    sn.groupia.payment.infrastructure.provider.saspay.SaspayTestConfiguration.class
})
class SaspayIntegrationTests {
    @Autowired sn.groupia.payment.infrastructure.provider.saspay.SaspayHttpStub http;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired PasswordEncoder passwords;
    @Autowired PaymentTestConfiguration.MutablePaymentClock clock;
    @Autowired PaymentService payments;
    @Autowired PaymentWebhooks webhooks;
    @Autowired sn.groupia.settlement.application.service.MerchantBalances balances;
    private static String passwordHash;
    private static final AtomicInteger addressSequence = new AtomicInteger();
    private UUID org, actor;
    private String jwt, remote, checkoutKey, quoteId;
    private JsonNode campaign, checkout;
    private final String providerId = "9c3f2a10-4b7e-4f1a-9d2e-9b6a7c1e4a02";

    @BeforeEach
    void setup() throws Exception {
        await(
                () ->
                        jdbc.queryForObject(
                                        "select count(*) from event_publication where status in ('PUBLISHED','PROCESSING','RESUBMITTED')",
                                        Long.class)
                                == 0);
        jdbc.execute(
                "TRUNCATE TABLE merchant_ledger_entries,outgoing_provider_operations,order_fulfillment_events, shipping_calculations, shipping_measurements, shipping_assessments, customer_access_sessions, otp_challenges, order_access_links, customer_access_limits, shipment_facts, shipments, supplier_order_anomalies, supplier_orders, payment_webhook_events,payments,payment_requests,order_items,orders,customers,grouping_changes,grouping_offers,grouping_campaigns,product_variants,products,organization_members,organizations,user_accounts,event_publication CASCADE");
        clock.reset();
        http.reset();
        remote = "192.0.2." + addressSequence.incrementAndGet();
        if (passwordHash == null) {
            passwordHash = passwords.encode("Financial fixture 2026!");
        }
        actor = UUID.randomUUID();
        jdbc.update(
                "insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at) values (?, '+221771234567', ?, 'ACTIVE', current_timestamp,current_timestamp)",
                actor,
                passwordHash);
        jwt = jwt(actor);
        org =
                UUID.fromString(
                        read(auth(post("/api/organizations"), Map.of("name", "Finance"))
                                        .andExpect(status().isCreated())
                                        .andReturn())
                                .get("id")
                                .asText());
        var product =
                read(
                        auth(
                                        post("/api/organizations/" + org + "/products"),
                                        Map.of("name", "Article"))
                                .andExpect(status().isCreated())
                                .andReturn());
        var base = "/api/organizations/" + org + "/grouping-campaigns";
        campaign =
                read(
                        auth(
                                        post(base),
                                        Map.of(
                                                "title",
                                                "Campagne",
                                                "shippingTerms",
                                                Map.of(
                                                        "transportMode",
                                                        "AIR",
                                                        "shippingPricingMethod",
                                                        "FIXED",
                                                        "shippingCollectionMode",
                                                        "AFTER_FINAL_ASSESSMENT",
                                                        "shippingRate",
                                                        1000)))
                                .andExpect(status().isCreated())
                                .andReturn());
        var path = base + "/" + campaign.get("id").asText();
        campaign =
                read(
                        auth(
                                        post(path + "/offers"),
                                        Map.of(
                                                "campaignVersion",
                                                campaign.get("version").asLong(),
                                                "productVariantId",
                                                product.get("variants").get(0).get("id").asText(),
                                                "unitPrice",
                                                1000))
                                .andExpect(status().isOk())
                                .andReturn());
        campaign =
                read(
                        auth(
                                        post(path + "/open"),
                                        Map.of("version", campaign.get("version").asLong()))
                                .andExpect(status().isOk())
                                .andReturn());
        checkoutKey = UUID.randomUUID().toString();
        checkout = read(place(checkoutKey).andExpect(status().isCreated()).andReturn());
    }

    private String quote() throws Exception {
        http.reply(
                200,
                sn.groupia.payment.infrastructure.provider.saspay.SaspayTestConfiguration.rates());
        var response =
                read(
                        mvc.perform(
                                        post(paymentPath().replace("/payments", "/payment-quotes"))
                                                .header("X-Order-Tracking-Token", secret())
                                                .with(
                                                        r -> {
                                                            r.setRemoteAddr(remote);
                                                            return r;
                                                        }))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(response.get(0).path("totalCharged").decimalValue())
                .isEqualByComparingTo("2050");
        assertThat(response.get(0).path("merchantNet").decimalValue()).isEqualByComparingTo("2000");
        assertThat(response.get(0).path("groupiaCommission").decimalValue())
                .isEqualByComparingTo("0");
        return quoteId = response.get(0).path("id").asText();
    }

    private void queueInitiation() {
        http.reply(
                200,
                sn.groupia.payment.infrastructure.provider.saspay.SaspayTestConfiguration.rates());
        http.reply(
                201,
                "{\"id\":\""
                        + providerId
                        + "\",\"status\":\"PENDING\",\"checkout_url\":\"https://pay.saspay.me/test-checkout\"}");
    }

    private String verification(String state) {
        return """
            {"id":"%s","merchant":"6f1e2b3a-8c4d-4a2f-9e1b-2d3c4f5a6b7c","transaction_type":"PAIEMENT",
            "flow_direction":"INBOUND","requested_amount":"2000.00","fee_charge_mode":"ADD_ON",
            "client_fee":"50.00","debited_amount":"2050.00","net_amount":"2000.00","currency":"XOF","status":"%s"}
            """
                .formatted(providerId, state);
    }

    private String event(String state) {
        return """
            {"event":"transaction.%s","data":{"id":"%s","type":"PAIEMENT","status":"%s","amount":"2000.00",
            "fee":"50.00","charged":"2050.00","net_amount":"2000.00","fee_charge_mode":"ADD_ON","currency":"XOF"}}
            """
                .formatted(
                        state.equals("SUCCESS")
                                ? "success"
                                : state.equals("FAILED") ? "failed" : "created",
                        providerId,
                        state);
    }

    private void deliver(String event) throws Exception {
        var bytes = event.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var timestamp = Long.toString(clock.instant().getEpochSecond());
        mvc.perform(
                        post("/api/payment/webhooks/saspay")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bytes)
                                .header("X-Webhook-Timestamp", timestamp)
                                .header(
                                        "X-Webhook-Signature",
                                        sn.groupia.payment.infrastructure.provider.saspay
                                                .SaspayTestConfiguration.signature(
                                                bytes, timestamp)))
                .andExpect(status().isAccepted());
    }

    @Test
    void quotedCollectionUsesSnapshotsAndDuplicateWebhookSettlesOnlyOnce() throws Exception {
        quote();
        queueInitiation();
        String key = key();
        var receipt = read(initiate(key).andExpect(status().isOk()).andReturn());
        initiate(key).andExpect(status().isOk());
        assertThat(http.requests).hasSize(3);
        var sent = http.requests.getLast();
        var body = json.readTree(sent.body());
        assertThat(sent.path()).isEqualTo("/api/v1/payments/softpay/");
        assertThat(sent.idempotencyKey()).isNotBlank();
        assertThat(body.path("amount").asText()).isEqualTo("2000.00");
        assertThat(body.path("fee_charge_mode").asText()).isEqualTo("ADD_ON");
        assertThat(body.path("customer").path("email").asText()).isEqualTo("awa@example.test");
        deliver(event("SUCCESS"));
        deliver(event("SUCCESS"));
        assertThat(jdbc.queryForObject("select count(*) from payment_webhook_events", Long.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select status from payments where id=?",
                                String.class,
                                UUID.fromString(receipt.path("id").asText())))
                .isEqualTo("PENDING");
        http.reply(200, verification("SUCCESS"));
        webhooks.retryPending();
        await(
                () ->
                        "CONFIRMED"
                                .equals(
                                        jdbc.queryForObject(
                                                "select status from orders where order_number=?",
                                                String.class,
                                                checkout.path("orderNumber").asText())));
        await(
                () ->
                        jdbc.queryForObject(
                                        "select count(*) from merchant_ledger_entries", Long.class)
                                == 1);
        jdbc.update(
                "update grouping_campaigns set status='CLOSED',closed_at=now() where id=?",
                UUID.fromString(campaign.path("id").asText()));
        var balance =
                balances.calculate(
                        org, UUID.fromString(campaign.path("id").asText()), true, true, true);
        assertThat(balance.grossMerchantCollections()).isEqualByComparingTo("2000");
        assertThat(balance.collectedBalance()).isEqualByComparingTo("2000");
        assertThat(balance.availableForPayout()).isZero();
        assertThat(balance.eligibleBalance()).isZero();
        deliver(event("PENDING"));
        webhooks.retryPending();
        assertThat(jdbc.queryForObject("select status from payments", String.class))
                .isEqualTo("SUCCEEDED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from event_publication where event_type like '%PaymentRequestSettled' and listener_id like '%OrderSettlementListener%'",
                                Long.class))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({
        "net_amount,1999.00",
        "requested_amount,1999.00",
        "client_fee,51.00",
        "debited_amount,2051.00",
        "currency,EUR",
        "fee_charge_mode,DEDUCTED",
        "merchant,00000000-0000-0000-0000-000000000000",
        "status,PENDING",
        "status,FAILED",
        "transaction_type,RETRAIT"
    })
    void inconsistentVerificationNeverSettles(String field, String value) throws Exception {
        quote();
        queueInitiation();
        initiate(key()).andExpect(status().isOk());
        deliver(event("SUCCESS"));
        var proof = (tools.jackson.databind.node.ObjectNode) json.readTree(verification("SUCCESS"));
        proof.put(field, value);
        http.reply(200, json.writeValueAsString(proof));
        webhooks.retryPending();
        assertThat(jdbc.queryForObject("select status from payments", String.class))
                .isNotEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select status from orders", String.class))
                .isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void earlyWebhookIsRetriedAfterTransactionBinding() throws Exception {
        deliver(event("SUCCESS"));
        webhooks.retryPending();
        assertThat(
                        jdbc.queryForObject(
                                "select processing_status from payment_webhook_events",
                                String.class))
                .isEqualTo("RETRYABLE_FAILURE");
        quote();
        queueInitiation();
        initiate(key()).andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(61));
        http.reply(200, verification("SUCCESS"));
        webhooks.retryPending();
        assertThat(jdbc.queryForObject("select status from payments", String.class))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void noQuoteExpiredQuoteAndWrongCapabilityNeverInitiate() throws Exception {
        quoteId = UUID.randomUUID().toString();
        initiate(key()).andExpect(status().isConflict());
        mvc.perform(
                        post(paymentPath().replace("/payments", "/payment-quotes"))
                                .header("X-Order-Tracking-Token", "bad-token"))
                .andExpect(status().isNotFound());
        quote();
        clock.advance(Duration.ofSeconds(121));
        initiate(key()).andExpect(status().isConflict());
        assertThat(http.requests).hasSize(1);
    }

    @Test
    void invalidSignatureAndClientAmountsCannotSettleOrCharge() throws Exception {
        mvc.perform(
                        post("/api/payment/webhooks/saspay")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(event("SUCCESS")))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        body(post(paymentPath()), Map.of("provider", "SASPAY", "amount", 1))
                                .header("X-Order-Tracking-Token", secret())
                                .header("Idempotency-Key", key()))
                .andExpect(status().isBadRequest());
        assertThat(http.requests).isEmpty();
    }

    @Test
    void signedFailureStillUsesAuthoritativeServerStatus() throws Exception {
        quote();
        queueInitiation();
        initiate(key()).andExpect(status().isOk());
        deliver(event("FAILED"));
        http.reply(200, verification("SUCCESS"));
        webhooks.retryPending();
        assertThat(jdbc.queryForObject("select status from payments", String.class))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void acceptedQuoteCannotBeUsedForAnotherOrder() throws Exception {
        quote();
        checkout = read(place(key()).andExpect(status().isCreated()).andReturn());
        initiate(key()).andExpect(status().isConflict());
        assertThat(http.requests).hasSize(1);
    }

    @Test
    void unknownTransactionExhaustsRetriesWithoutChangingAnOrder() throws Exception {
        deliver(event("SUCCESS"));
        for (int n = 0; n < 5; n++) {
            webhooks.retryPending();
            clock.advance(Duration.ofMinutes(5));
        }
        assertThat(
                        jdbc.queryForObject(
                                "select processing_status from payment_webhook_events",
                                String.class))
                .isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("select status from orders", String.class))
                .isEqualTo("PENDING_PAYMENT");
        assertThat(http.requests).isEmpty();
    }

    @Test
    void changedRateBlocksSoftpayAndKeepsClaimForInvestigation() throws Exception {
        quote();
        http.reply(
                200,
                sn.groupia.payment.infrastructure.provider.saspay.SaspayTestConfiguration.rates()
                        .replace("2.500", "3.000"));
        var receipt = read(initiate(key()).andExpect(status().isOk()).andReturn());
        assertThat(receipt.path("reconciliationRequired").asBoolean()).isTrue();
        assertThat(http.requests).hasSize(2);
        assertThat(http.requests).allMatch(r -> r.method().equals("GET"));
        initiate(key()).andExpect(status().isConflict());
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private String secret() {
        return checkout.get("trackingToken").asText();
    }

    private String paymentPath() {
        return "/api/public/orders/" + checkout.get("orderNumber").asText() + "/payments";
    }

    private ResultActions initiate(String key) throws Exception {
        return mvc.perform(
                body(post(paymentPath()), Map.of("provider", "SASPAY", "quoteId", quoteId))
                        .header("X-Order-Tracking-Token", secret())
                        .header("Idempotency-Key", key)
                        .with(
                                r -> {
                                    r.setRemoteAddr(remote);
                                    return r;
                                }));
    }

    private ResultActions place(String key) throws Exception {
        return mvc.perform(
                body(
                                post(
                                        "/api/public/groupings/"
                                                + campaign.get("publicIdentifier").asText()
                                                + "/checkout"),
                                Map.of(
                                        "contact",
                                        Map.of(
                                                "firstName",
                                                "Awa",
                                                "lastName",
                                                "Diop",
                                                "phone",
                                                "771234567",
                                                "email",
                                                "awa@example.test"),
                                        "lines",
                                        List.of(
                                                Map.of(
                                                        "offerId",
                                                        campaign.get("offers")
                                                                .get(0)
                                                                .get("id")
                                                                .asText(),
                                                        "quantity",
                                                        2,
                                                        "expectedUnitPrice",
                                                        1000,
                                                        "expectedPriceRevision",
                                                        0))))
                        .header("Idempotency-Key", key));
    }

    private MockHttpServletRequestBuilder body(
            MockHttpServletRequestBuilder request, Object value) {
        return request.contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(value));
    }

    private ResultActions auth(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + jwt));
    }

    private ResultActions auth(MockHttpServletRequestBuilder request, Object value)
            throws Exception {
        return auth(body(request, value));
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String jwt(UUID id) {
        var claims =
                JwtClaimsSet.builder()
                        .claim("sid", UUID.randomUUID().toString())
                        .issuer("https://groupia.test")
                        .audience(List.of("groupia-api-test"))
                        .subject(id.toString())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(1800))
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
