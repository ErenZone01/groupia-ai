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

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(
        properties = {
            "groupia.direct-sales.payment-initiation.enabled=true",
            "groupia.payment.saspay.legacy-softpay-tests=true"
        })
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
                "TRUNCATE TABLE payout_status_notifications,merchant_ledger_entries,outgoing_provider_operations,order_fulfillment_events, shipping_calculations, shipping_measurements, shipping_assessments, customer_access_sessions, otp_challenges, order_access_links, customer_access_limits, shipment_facts, shipments, supplier_order_anomalies, supplier_orders, payment_webhook_events,payments,payment_requests,order_items,orders,customers,grouping_changes,grouping_offers,grouping_campaigns,product_variants,products,organization_members,organizations,user_accounts,event_publication CASCADE");
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
    void payoutWebhooksAreDurableDeduplicatedHintsAndNeverAllocatePayins() throws Exception {
        String outgoing = event("SUCCESS").replace("PAIEMENT", "RETRAIT");
        deliver(outgoing);
        deliver(outgoing);
        deliver(event("PENDING").replace("PAIEMENT", "RETRAIT"));
        deliver(event("FAILED").replace("PAIEMENT", "RETRAIT"));
        await(
                () ->
                        jdbc.queryForObject(
                                        "select count(*) from event_publication where status in ('PUBLISHED','PROCESSING','RESUBMITTED')",
                                        Long.class)
                                == 0);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payout_status_notifications where authentic",
                                Long.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from payment_webhook_events", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from merchant_payouts", Long.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from merchant_payout_verifications", Long.class))
                .isZero();
    }

    @Test
    void payoutNotificationAuthenticationAndPersistenceCannotBeBypassed() throws Exception {
        String outgoing = event("SUCCESS").replace("PAIEMENT", "RETRAIT");
        mvc.perform(
                        post("/api/payment/webhooks/saspay")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(outgoing)
                                .header("X-Webhook-Timestamp", clock.instant().getEpochSecond())
                                .header("X-Webhook-Signature", "invalid"))
                .andExpect(status().isUnauthorized());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payout_status_notifications where not authentic",
                                Long.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from event_publication where event_type like '%PayoutStatusNotificationReceived'",
                                Long.class))
                .isZero();
        deliver(outgoing.replace(providerId, "invalid-id"));
        assertThat(
                        jdbc.queryForObject(
                                "select error_code from payout_status_notifications where authentic",
                                String.class))
                .isEqualTo("PAYOUT_NOTIFICATION_INVALID");
        assertThat(jdbc.queryForObject("select count(*) from payment_webhook_events", Long.class))
                .isZero();
    }

    @Test
    void hostedCheckoutFinancialEvidenceMigrationKeepsPrincipalAndFeeIndependent() {
        var names =
                jdbc.queryForList(
                        "select column_name from information_schema.columns"
                                + " where table_schema=current_schema() and table_name='payments'"
                                + " and column_name in ('provider_collection_fee','provider_customer_charged')",
                        String.class);
        assertThat(names)
                .containsExactlyInAnyOrder("provider_collection_fee", "provider_customer_charged");
        var constraint =
                jdbc.queryForObject(
                        "select pg_get_constraintdef(c.oid) from pg_constraint c"
                                + " where c.conrelid='payments'::regclass"
                                + " and c.conname='ck_payment_hosted_fee_amounts'",
                        String.class);
        assertThat(constraint)
                .contains("provider_collection_fee")
                .contains("provider_customer_charged")
                .contains("expected_amount")
                .contains("SUCCEEDED");
    }

    @Test
    void fractionalSaspayCollectionLedgerUsesExactCentsWithoutWeakeningPrincipal() {
        String collectionFeeConstraint =
                jdbc.queryForObject(
                        "select pg_get_constraintdef(c.oid) from pg_constraint c"
                                + " where c.conrelid='provider_collections'::regclass"
                                + " and c.conname='provider_collections_collection_fee_precision_check'",
                        String.class);
        String totalConstraint =
                jdbc.queryForObject(
                        "select pg_get_constraintdef(c.oid) from pg_constraint c"
                                + " where c.conrelid='provider_collections'::regclass"
                                + " and c.conname='provider_collections_customer_charged_precision_check'",
                        String.class);
        assertThat(collectionFeeConstraint).contains("round(collection_fee, 2)");
        assertThat(totalConstraint).contains("round(customer_charged, 2)");
        // Original immutable account invariants must still be enforced.
        var principal =
                jdbc.queryForObject(
                        "select pg_get_constraintdef(c.oid) from pg_constraint c"
                                + " where c.conrelid='provider_collections'::regclass"
                                + " and c.conname='provider_collections_principal_check'",
                        String.class);
        assertThat(principal).contains("trunc(principal)");
    }

    @Test
    void fractionalSaspayFeeMigrationPersistsExactCentsAndRejectsExtraPrecision() {
        UUID requestId =
                jdbc.queryForObject(
                        "select id from payment_requests where organization_id=? limit 1",
                        UUID.class,
                        org);
        String insert =
                "insert into payment_fee_quotes(id,organization_id,request_id,provider,"
                        + "account,country,network,label,amount,fee,currency,expires_at)"
                        + " values (?,?,?,'SASPAY',?,'SN','wave_sn','Wave',1000,?,'XOF',?)";
        UUID id = UUID.randomUUID();
        jdbc.update(
                insert,
                id,
                org,
                requestId,
                sn.groupia.payment.infrastructure.provider.saspay.SaspayTestConfiguration.MERCHANT,
                new java.math.BigDecimal("22.50"),
                java.sql.Timestamp.from(clock.instant().plusSeconds(120)));
        assertThat(
                        jdbc.queryForObject(
                                "select fee from payment_fee_quotes where id=?",
                                java.math.BigDecimal.class,
                                id))
                .isEqualByComparingTo("22.50");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        insert,
                                        UUID.randomUUID(),
                                        org,
                                        requestId,
                                        sn.groupia.payment.infrastructure.provider.saspay
                                                .SaspayTestConfiguration.MERCHANT,
                                        new java.math.BigDecimal("22.501"),
                                        java.sql.Timestamp.from(clock.instant().plusSeconds(120))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
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
        await(
                () ->
                        jdbc.queryForObject("select count(*) from provider_collections", Long.class)
                                == 1);
        var collection = jdbc.queryForMap("select * from provider_collections");
        assertThat((java.math.BigDecimal) collection.get("seller_principal"))
                .isEqualByComparingTo("2000");
        assertThat((java.math.BigDecimal) collection.get("collection_fee"))
                .isEqualByComparingTo("50");
        assertThat((java.math.BigDecimal) collection.get("customer_charged"))
                .isEqualByComparingTo("2050");
        assertThat(collection.get("provider")).isEqualTo("SASPAY");
        auth(post("/api/organizations/" + org + "/provider-collections/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("recorded").value(0));

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

    @Autowired sn.groupia.directsales.application.DirectPaymentObligations directObligations;

    record DirectFixture(UUID orderId, String token, UUID requestId) {}

    private DirectFixture directFixture() throws Exception {
        UUID storefront = UUID.randomUUID();
        jdbc.update(
                "insert into direct_storefronts"
                        + " (id,organization_id,public_identifier,display_name,created_at,updated_at)"
                        + " values (?,?,?,'Direct Sandbox',current_timestamp,current_timestamp)",
                storefront,
                org,
                UUID.randomUUID());

        UUID order = UUID.randomUUID();
        String number = "DIR-000000000001";
        byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        String recovery = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        jdbc.update(
                "insert into direct_orders"
                        + " (id,organization_id,storefront_id,order_number,status,"
                        + "fulfillment_method,currency,customer_contact_snapshot,"
                        + "delivery_address_snapshot,goods_subtotal_xof,platform_fee_xof,"
                        + "platform_fee_rule_snapshot,amount_before_shipping_xof,shipping_quoted,"
                        + "shipping_fee_xof,accepted_total_xof,checkout_idempotency_key,"
                        + "checkout_request_fingerprint,placed_at,recovery_token_hash)"
                        + " values (?,?,?,?,'PENDING_PAYMENT_INTEGRATION','PICKUP','XOF',"
                        + "cast(? as jsonb),null,10000,0,cast(? as jsonb),10000,true,0,10000,"
                        + "?, ?,current_timestamp,?)",
                order,
                org,
                storefront,
                number,
                json.writeValueAsString(
                        Map.of(
                                "firstName",
                                "Awa",
                                "lastName",
                                "Ndiaye",
                                "phoneE164",
                                "+221771234567",
                                "email",
                                "awa@example.test")),
                "{\"type\":\"FIXED\",\"amount\":\"0\"}",
                "sandbox-checkout-key-0001",
                "a".repeat(64),
                sn.groupia.directsales.application.DirectAccessTokens.hashForCreate(recovery));
        jdbc.update(
                "insert into direct_order_items"
                        + " (id,organization_id,order_id,offer_id_snapshot,product_id_snapshot,"
                        + "product_variant_id_snapshot,product_name_snapshot,"
                        + "variant_attributes_snapshot,line_number,quantity,unit_price_xof,"
                        + "line_total_xof,price_revision_snapshot)"
                        + " values (?,?,?,?,?,?,'Article démo','{}',1,1,10000,10000,0)",
                UUID.randomUUID(),
                org,
                order,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID());

        jdbc.update(
                "update direct_orders set placed_at=? where id=?",
                java.sql.Timestamp.from(clock.instant()),
                order);
        var prepared = directObligations.prepare(org, order);
        assertThat(prepared.expectedAmount())
                .isEqualByComparingTo(java.math.BigDecimal.valueOf(10000));
        return new DirectFixture(order, recovery, prepared.id());
    }

    private ResultActions directPost(String route, Map<String, Object> body, String key)
            throws Exception {
        return mvc.perform(
                post("/api/public/direct-orders/" + route)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)));
    }

    @Test
    void directSaspayUsesPrivateTokenAndVerifiedNetWithoutTouchingGroupageOrSubscription()
            throws Exception {
        var direct = directFixture();
        directPost(
                        "payment-quotes",
                        Map.of("token", direct.orderId().toString()),
                        "invalid-token-key")
                .andExpect(status().isNotFound());
        assertThat(http.requests).isEmpty();
        http.reply(
                200,
                sn.groupia.payment.infrastructure.provider.saspay.SaspayTestConfiguration.rates());
        var quotes =
                read(
                        directPost(
                                        "payment-quotes",
                                        Map.of("token", direct.token()),
                                        "quote-key-0000001")
                                .andExpect(status().isOk())
                                .andExpect(header().string("Cache-Control", "no-store"))
                                .andReturn());
        String fee = quotes.get(0).path("id").asText();
        assertThat(quotes.get(0).path("merchantNet").decimalValue()).isEqualByComparingTo("10000");
        assertThat(quotes.get(0).path("totalCharged").decimalValue()).isEqualByComparingTo("10250");
        queueInitiation();
        var body = Map.<String, Object>of("token", direct.token(), "feeQuoteId", fee);
        directPost("payments", body, "direct-saspay-key-0001").andExpect(status().isOk());
        directPost("payments", body, "direct-saspay-key-0001").andExpect(status().isOk());
        assertThat(http.requests.stream().filter(r -> r.method().equals("POST")).count())
                .isEqualTo(1);
        directPost("payment-status", Map.of("token", direct.token()), "status-key-00001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestAttempt.provider").value("SASPAY"))
                .andExpect(jsonPath("$.financialStatus").value("PAYMENT_IN_PROGRESS"));
        deliver(subscriptionAmounts(event("SUCCESS")));
        deliver(subscriptionAmounts(event("SUCCESS")));
        http.reply(200, subscriptionAmounts(verification("SUCCESS")));
        webhooks.retryPending();
        await(
                () ->
                        jdbc.queryForObject(
                                        "select count(*) from direct_order_verified_settlements where direct_order_id=?",
                                        Long.class,
                                        direct.orderId())
                                == 1);
        directPost("payment-status", Map.of("token", direct.token()), "status-key-00001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financialStatus").value("SETTLEMENT_VERIFIED"));
        assertThat(
                        jdbc.queryForObject(
                                "select status from direct_orders where id=?",
                                String.class,
                                direct.orderId()))
                .isEqualTo("PAYMENT_CONFIRMED");
        assertThat(jdbc.queryForObject("select count(*) from subscription_periods", Long.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from orders where status='CONFIRMED'", Long.class))
                .isZero();
    }

    @Test
    void directSaspayQuotesAcceptNumericJsonRateFieldsWithoutInitiatingPayment() throws Exception {
        var direct = directFixture();
        var numericRates =
                sn.groupia.payment.infrastructure.provider.saspay.SaspayTestConfiguration.rates()
                        .replace("\"min_amount\":\"0.00\"", "\"min_amount\":0.00")
                        .replace("\"max_amount\":\"500000.00\"", "\"max_amount\":500000.00")
                        .replace("\"percent\":\"2.500\"", "\"percent\":2.500")
                        .replace("\"fixed\":\"0.00\"", "\"fixed\":0.00");
        http.reply(200, numericRates);
        var quotes =
                read(
                        directPost(
                                        "payment-quotes",
                                        Map.of("token", direct.token()),
                                        "direct-numeric-rates")
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(quotes.size()).isEqualTo(1);
        assertThat(quotes.get(0).path("totalCharged").decimalValue()).isEqualByComparingTo("10250");
        assertThat(http.requests.stream().filter(r -> r.method().equals("POST")).count()).isZero();
    }

    @Test
    void missingDirectBuyerEmailIsExplicitlyRejectedBeforeRequestingSaspayRates() throws Exception {
        var direct = directFixture();
        jdbc.update(
                "update direct_orders set customer_contact_snapshot = customer_contact_snapshot - 'email' where id=?",
                direct.orderId());
        directPost("payment-quotes", Map.of("token", direct.token()), "direct-missing-email")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SASPAY_PAYER_REQUIRED"));
        assertThat(http.requests).isEmpty();
    }

    @Test
    void incompatibleDirectNetworkIsReportedRatherThanReturningSilentEmptyQuotes()
            throws Exception {
        var direct = directFixture();
        http.reply(
                200,
                sn.groupia.payment.infrastructure.provider.saspay.SaspayTestConfiguration.rates()
                        .replace("\"otp_required\":false", "\"otp_required\":true"));
        directPost(
                        "payment-quotes",
                        Map.of("token", direct.token()),
                        "direct-no-compatible-network")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SASPAY_NO_COMPATIBLE_QUOTE"));
        assertThat(http.requests.stream().filter(r -> r.method().equals("POST")).count()).isZero();
    }

    @Test
    void directSaspayRejectsAnotherTargetsQuoteAndChangedHistoricalAmount() throws Exception {
        var direct = directFixture();
        String foreign = quote();
        directPost(
                        "payments",
                        Map.of("token", direct.token(), "feeQuoteId", foreign),
                        "direct-cross-quote-01")
                .andExpect(status().isConflict());
        http.reply(
                200,
                sn.groupia.payment.infrastructure.provider.saspay.SaspayTestConfiguration.rates());
        var quotes =
                read(
                        directPost(
                                        "payment-quotes",
                                        Map.of("token", direct.token()),
                                        "quote-key-0000001")
                                .andExpect(status().isOk())
                                .andReturn());
        jdbc.update(
                "update direct_order_items set unit_price_xof=11000,line_total_xof=11000 where order_id=?",
                direct.orderId());
        directPost(
                        "payments",
                        Map.of(
                                "token",
                                direct.token(),
                                "feeQuoteId",
                                quotes.get(0).path("id").asText()),
                        "direct-changed-amount")
                .andExpect(status().isConflict());
        assertThat(http.requests.stream().filter(r -> r.method().equals("POST")).count()).isZero();
    }

    private String subscriptionBase() {
        return "/api/organizations/" + org + "/subscription";
    }

    private JsonNode subscriptionInvoice() throws Exception {
        auth(
                        put(subscriptionBase() + "/billing-contact"),
                        Map.of(
                                "name",
                                "Awa Diop",
                                "phoneE164",
                                "+221771234567",
                                "email",
                                "awa@example.test"))
                .andExpect(status().isOk());
        return read(
                auth(post(subscriptionBase() + "/invoices").header("Idempotency-Key", key()))
                        .andExpect(status().isOk())
                        .andReturn());
    }

    private String subscriptionQuote(JsonNode invoice) throws Exception {
        http.reply(
                200,
                sn.groupia.payment.infrastructure.provider.saspay.SaspayTestConfiguration.rates());
        var quotes =
                read(
                        auth(post(
                                        subscriptionBase()
                                                + "/invoices/"
                                                + invoice.path("id").asText()
                                                + "/payment-quotes"))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(quotes.get(0).path("saleAmount").decimalValue()).isEqualByComparingTo("10000");
        assertThat(quotes.get(0).path("totalCharged").decimalValue()).isEqualByComparingTo("10250");
        return quotes.get(0).path("id").asText();
    }

    private ResultActions paySubscription(JsonNode invoice, String quote, String key)
            throws Exception {
        return auth(
                post(subscriptionBase() + "/invoices/" + invoice.path("id").asText() + "/payment")
                        .header("Idempotency-Key", key),
                Map.of("provider", "SASPAY", "feeQuoteId", quote));
    }

    private String subscriptionAmounts(String payload) {
        return payload.replace("2050.00", "10250.00")
                .replace("2000.00", "10000.00")
                .replace("\"50.00\"", "\"250.00\"");
    }

    @Test
    void subscriptionSaspayActivatesOnceAndDoesNotConfirmAnOrder() throws Exception {
        var details = read(auth(get(subscriptionBase())).andExpect(status().isOk()).andReturn());
        assertThat(details.path("availableProviders").toString()).contains("SASPAY");
        var trialEnd = Instant.parse(details.path("access").path("trialEndsAt").asText());
        clock.advance(Duration.between(clock.instant(), trialEnd).plusSeconds(1));
        var invoice = subscriptionInvoice();
        var quote = subscriptionQuote(invoice);
        queueInitiation();
        var attemptKey = key();
        paySubscription(invoice, quote, attemptKey).andExpect(status().isOk());
        paySubscription(invoice, quote, attemptKey).andExpect(status().isOk());
        assertThat(http.requests.stream().filter(r -> r.method().equals("POST")).count())
                .isEqualTo(1);
        var recovery =
                read(
                        auth(get(subscriptionBase() + "/invoices/" + invoice.path("id").asText()))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(recovery.path("latestAttempt").path("feeQuoteId").asText()).isEqualTo(quote);
        assertThat(recovery.path("latestAttempt").path("attempt").path("checkoutUrl").asText())
                .isEqualTo("https://pay.saspay.me/test-checkout");
        deliver(subscriptionAmounts(event("SUCCESS")));
        deliver(subscriptionAmounts(event("SUCCESS")));
        http.reply(200, subscriptionAmounts(verification("SUCCESS")));
        webhooks.retryPending();
        await(
                () ->
                        jdbc.queryForObject(
                                        "select count(*) from subscription_periods where organization_id=?",
                                        Long.class,
                                        org)
                                == 1);
        var active = read(auth(get(subscriptionBase())).andExpect(status().isOk()).andReturn());
        assertThat(active.path("access").path("status").asText()).isEqualTo("ACTIVE");
        assertThat(active.path("renewal").path("manualRenewal").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select status from orders", String.class))
                .isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("select count(*) from merchant_ledger_entries", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("select status from subscription_invoices", String.class))
                .isEqualTo("PAID");
        auth(post("/api/organizations/" + org + "/provider-collections/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("recorded").value(0));
        assertThat(jdbc.queryForObject("select count(*) from provider_collections", Long.class))
                .isZero();

        paySubscription(invoice, quote, key()).andExpect(status().isConflict());
    }

    @Test
    void subscriptionQuoteCannotPayAnOrderOrAnInvoiceInAnotherOrganization() throws Exception {
        var invoice = subscriptionInvoice();
        quoteId = subscriptionQuote(invoice);
        initiate(key()).andExpect(status().isConflict());
        var other =
                read(auth(post("/api/organizations"), Map.of("name", "Autre boutique"))
                                .andExpect(status().isCreated())
                                .andReturn())
                        .path("id")
                        .asText();
        auth(post(
                        "/api/organizations/"
                                + other
                                + "/subscription/invoices/"
                                + invoice.path("id").asText()
                                + "/payment-quotes"))
                .andExpect(status().isNotFound());
        assertThat(http.requests).hasSize(1);
    }

    @Test
    void subscriptionQuoteRequiresPayerContactAndDoesNotRewriteIssuedInvoice() throws Exception {
        auth(
                        put(subscriptionBase() + "/billing-contact"),
                        Map.of("name", "Boutique", "phoneE164", "+221771234567"))
                .andExpect(status().isOk());
        var invoice =
                read(
                        auth(post(subscriptionBase() + "/invoices")
                                        .header("Idempotency-Key", key()))
                                .andExpect(status().isOk())
                                .andReturn());
        var path =
                subscriptionBase() + "/invoices/" + invoice.path("id").asText() + "/payment-quotes";
        assertThat(read(auth(post(path)).andExpect(status().isOk()).andReturn()).size()).isZero();
        assertThat(http.requests).isEmpty();
        auth(
                        put(subscriptionBase() + "/billing-contact"),
                        Map.of(
                                "name",
                                "Awa Diop",
                                "phoneE164",
                                "+221771234567",
                                "email",
                                "awa@example.test"))
                .andExpect(status().isOk());
        subscriptionQuote(invoice);
        assertThat(
                        jdbc.queryForObject(
                                "select billing_name from subscription_invoices", String.class))
                .isEqualTo("Boutique");
        assertThat(
                        jdbc.queryForObject(
                                "select billing_email from subscription_invoices", String.class))
                .isNull();
    }

    private UUID commercialOrderId() {
        return jdbc.queryForObject(
                "select id from orders where order_number=?",
                UUID.class,
                checkout.path("orderNumber").asText());
    }

    private void settleCommercialOrder() throws Exception {
        quote();
        queueInitiation();
        initiate(key()).andExpect(status().isOk());
        deliver(event("SUCCESS"));
        http.reply(200, verification("SUCCESS"));
        webhooks.retryPending();
        await(
                () ->
                        "CONFIRMED"
                                .equals(
                                        jdbc.queryForObject(
                                                "select status from orders where id=?",
                                                String.class,
                                                commercialOrderId())));
    }

    @Test
    void cancelledPaidGroupageKeepsSaspayProofAndSeparatesExternalRefundFromProviderFees()
            throws Exception {
        settleCommercialOrder();
        var order = commercialOrderId();
        var path = "/api/organizations/" + org + "/orders/" + order + "/cancellation";
        long version =
                jdbc.queryForObject("select version from orders where id=?", Long.class, order);
        var key = UUID.randomUUID();
        var command = Map.of("version", version, "reason", "Demande cliente", "confirmed", true);
        int calls = http.requests.size();
        var result =
                read(
                        auth(post(path).header("Idempotency-Key", key), command)
                                .andExpect(status().isOk())
                                .andReturn());
        auth(post(path).header("Idempotency-Key", key), command).andExpect(status().isOk());
        var id = result.path("caseId").asText();
        var refundPath = "/api/organizations/" + org + "/commercial-refunds/" + id;
        auth(get(refundPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("refund.amountDue").value(2000))
                .andExpect(jsonPath("refund.legacyPlatformFee").value(0))
                .andExpect(jsonPath("providerRefundAvailable").value(false));
        auth(
                        post(refundPath + "/external-declaration")
                                .header("Idempotency-Key", UUID.randomUUID()),
                        Map.of(
                                "version",
                                0,
                                "externalMethod",
                                "CASH",
                                "note",
                                "Restitution fictive pour test local",
                                "confirmed",
                                true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("historicalFeeCompensationDue").value(0));
        await(
                () ->
                        "REFUNDED"
                                .equals(
                                        jdbc.queryForObject(
                                                "select status from orders where id=?",
                                                String.class,
                                                order)));
        assertThat(http.requests).hasSize(calls);
        assertThat(jdbc.queryForObject("select status from payments", String.class))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select count(*) from commercial_refund_cases", Long.class))
                .isEqualTo(1);
        assertThat(
                        balances.calculate(
                                        org,
                                        UUID.fromString(campaign.path("id").asText()),
                                        true,
                                        true,
                                        true)
                                .availableForPayout())
                .isZero();
        initiate(key()).andExpect(status().isConflict());
    }

    @Test
    void campaignCancellationCreatesUnpaidCasesAndRejectsNewPaymentClaims() throws Exception {
        quote();
        var path =
                "/api/organizations/" + org + "/grouping-campaigns/" + campaign.path("id").asText();
        auth(
                        post(path + "/cancel"),
                        Map.of(
                                "version",
                                campaign.path("version").asLong(),
                                "reason",
                                "Annulation test"))
                .andExpect(status().isOk());
        await(
                () ->
                        jdbc.queryForObject(
                                        "select count(*) from commercial_refund_cases", Long.class)
                                == 1);
        assertThat(jdbc.queryForObject("select status from commercial_refund_cases", String.class))
                .isEqualTo("NO_REFUND_DUE");
        assertThat(
                        jdbc.queryForObject(
                                "select status from orders where id=?",
                                String.class,
                                commercialOrderId()))
                .isEqualTo("CANCELLED");
        int calls = http.requests.size();
        initiate(key()).andExpect(status().isConflict());
        assertThat(http.requests).hasSize(calls);
    }

    @Test
    void uncertainCollectionOnCancelledCampaignResolvesToRefundAfterVerifiedLateSuccess()
            throws Exception {
        quote();
        queueInitiation();
        initiate(key()).andExpect(status().isOk());
        var path =
                "/api/organizations/" + org + "/grouping-campaigns/" + campaign.path("id").asText();
        auth(
                        post(path + "/cancel"),
                        Map.of(
                                "version",
                                campaign.path("version").asLong(),
                                "reason",
                                "Annulation test"))
                .andExpect(status().isOk());
        TestEventDrain.awaitIdle(jdbc);
        assertThat(jdbc.queryForObject("select count(*) from commercial_refund_cases", Long.class))
                .isZero();
        deliver(event("SUCCESS"));
        http.reply(200, verification("SUCCESS"));
        webhooks.retryPending();
        await(
                () ->
                        "REFUND_PENDING"
                                .equals(
                                        jdbc.queryForObject(
                                                "select status from orders where id=?",
                                                String.class,
                                                commercialOrderId())));
        assertThat(
                        jdbc.queryForObject(
                                "select amount_due from commercial_refund_cases",
                                java.math.BigDecimal.class))
                .isEqualByComparingTo("2000");
        assertThat(jdbc.queryForObject("select status from payments", String.class))
                .isEqualTo("SUCCEEDED");
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
