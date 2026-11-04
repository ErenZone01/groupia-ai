package sn.groupia;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
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
import sn.groupia.directsales.application.DirectAccessTokens;
import sn.groupia.directsales.application.DirectPaymentObligations;
import sn.groupia.payment.infrastructure.provider.bictorys.BictorysHttpStub;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end proof with real PostgreSQL, direct orders and a synthetic local HTTP Bictorys server.
 * No real checkout funds, real API key or live provider network access.
 */
@SpringBootTest(
        properties = {
            "groupia.direct-sales.payment-initiation.enabled=true",
            "groupia.payment.bictorys.enabled=false"
        })
@AutoConfigureMockMvc
@Import({
    TestcontainersConfiguration.class,
    sn.groupia.payment.infrastructure.provider.bictorys.BictorysTestServerConfiguration.class
})
class DirectBictorysSandboxIntegrationTests {
    private static final String PAYMENT_URL = "/api/public/direct-orders/payments";
    private static final String FINANCIAL_URL = "/api/public/direct-orders/payment-status";
    private static final String CHECKOUT_KEY = "direct-sandbox-checkout-0001";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired PasswordEncoder passwords;
    @Autowired DirectPaymentObligations obligations;
    @Autowired BictorysHttpStub bictorys;

    private UUID org;
    private UUID order;
    private String number;
    private String recovery;
    private String bearer;

    @BeforeEach
    void setup() throws Exception {
        TestEventDrain.awaitIdle(jdbc);
        jdbc.execute(
                "TRUNCATE TABLE outgoing_provider_operations,order_fulfillment_events,"
                        + "shipping_calculations,shipping_measurements,shipping_assessments,"
                        + "customer_access_sessions,otp_challenges,order_access_links,"
                        + "customer_access_limits,shipment_facts,shipments,"
                        + "supplier_order_anomalies,supplier_orders,payment_webhook_events,"
                        + "payments,payment_requests,order_items,orders,customers,"
                        + "grouping_changes,grouping_offers,grouping_campaigns,"
                        + "product_variants,products,organization_members,organizations,"
                        + "user_accounts,event_publication CASCADE");
        bictorys.reset();

        UUID user = UUID.randomUUID();
        jdbc.update(
                "insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at)"
                        + " values (?,? ,?,'ACTIVE',current_timestamp,current_timestamp)",
                user,
                "+221771234567",
                passwords.encode("Direct integration fixture password 2026"));
        bearer = token(user);
        org =
                UUID.fromString(
                        body(mvc.perform(
                                                post("/api/organizations")
                                                        .header("Authorization", "Bearer " + bearer)
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                json.writeValueAsString(
                                                                        Map.of(
                                                                                "name",
                                                                                "Direct Sandbox"))))
                                        .andExpect(status().isCreated())
                                        .andReturn())
                                .path("id")
                                .asText());

        UUID storefront = UUID.randomUUID();
        jdbc.update(
                "insert into direct_storefronts"
                        + " (id,organization_id,public_identifier,display_name,created_at,updated_at)"
                        + " values (?,?,?,'Direct Sandbox',current_timestamp,current_timestamp)",
                storefront,
                org,
                UUID.randomUUID());

        order = UUID.randomUUID();
        number = "DIR-" + String.format(Locale.ROOT, "%012d", RANDOM.nextLong(1, 999999999999L));
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        recovery = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        jdbc.update(
                "insert into direct_orders"
                        + " (id,organization_id,storefront_id,order_number,status,"
                        + "fulfillment_method,currency,customer_contact_snapshot,"
                        + "delivery_address_snapshot,goods_subtotal_xof,platform_fee_xof,"
                        + "platform_fee_rule_snapshot,amount_before_shipping_xof,shipping_quoted,"
                        + "shipping_fee_xof,accepted_total_xof,checkout_idempotency_key,"
                        + "checkout_request_fingerprint,placed_at,recovery_token_hash)"
                        + " values (?,?,?,?,'PENDING_PAYMENT_INTEGRATION','PICKUP','XOF',"
                        + "cast(? as jsonb),null,10000,300,cast(? as jsonb),10300,true,0,10300,"
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
                                "+221771234567")),
                "{\"type\":\"FIXED\",\"amount\":\"300\"}",
                "sandbox-checkout-key-0001",
                "a".repeat(64),
                DirectAccessTokens.hashForCreate(recovery));
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

        var prepared = obligations.prepare(org, order);
        assertThat(prepared.expectedAmount()).isEqualByComparingTo(BigDecimal.valueOf(10300));
    }

    private String token(UUID user) {
        var claims =
                JwtClaimsSet.builder()
                        .claim("sid", UUID.randomUUID().toString())
                        .issuer("https://groupia.test")
                        .audience(List.of("groupia-api-test"))
                        .subject(user.toString())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(1800))
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private ResultActions initiate(String token, String key) throws Exception {
        return mvc.perform(
                post(PAYMENT_URL)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", token))));
    }

    private ResultActions financial(String token) throws Exception {
        return mvc.perform(
                post(FINANCIAL_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", token))));
    }

    private void queueCharge() throws Exception {
        String charge = UUID.randomUUID().toString();
        bictorys.reply(
                202,
                json.writeValueAsString(
                        Map.of(
                                "type",
                                "CheckoutLinkObject",
                                "chargeId",
                                charge,
                                "link",
                                "https://pay.test.bictorys.com/v1/charges/" + charge)));
    }

    private String successPayload(String transaction, long amount) throws Exception {
        String merchantReference =
                jdbc.queryForObject(
                        "select merchant_reference from payments where payment_request_id="
                                + "(select id from payment_requests where direct_order_id=?)",
                        String.class,
                        order);
        return json.writeValueAsString(
                Map.of(
                        "id",
                        transaction,
                        "type",
                        "payment",
                        "merchantReference",
                        merchantReference,
                        "paymentReference",
                        number,
                        "amount",
                        amount,
                        "currency",
                        "XOF",
                        "status",
                        "succeeded",
                        "timestamp",
                        Instant.now().toString()));
    }

    private ResultActions webhook(String payload, String secret) throws Exception {
        return mvc.perform(
                post("/api/payment/webhooks/bictorys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Secret-Key", secret)
                        .content(payload));
    }

    private String orderStatus() {
        return jdbc.queryForObject(
                "select status from direct_orders where id=?", String.class, order);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private void await(BooleanSupplier condition) throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < limit) {
            Thread.sleep(30);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void privateLinkStartsOneBictorysChargeWithoutFakingSuccessfulPayment() throws Exception {
        financial(recovery)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financialStatus").value("PREPARED"))
                .andExpect(jsonPath("$.paymentAvailable").value(true));
        queueCharge();
        var first =
                body(
                        initiate(recovery, CHECKOUT_KEY)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("PENDING"))
                                .andReturn());
        var replay = body(initiate(recovery, CHECKOUT_KEY).andExpect(status().isOk()).andReturn());
        assertThat(first.path("checkoutUrl").asText()).startsWith("https://pay.test.bictorys.com/");
        assertThat(replay).isEqualTo(first);
        assertThat(bictorys.requests).hasSize(1);
        JsonNode outbound = json.readTree(bictorys.requests.getFirst().body());
        assertThat(outbound.path("amount").asLong()).isEqualTo(10300);
        assertThat(outbound.path("currency").asText()).isEqualTo("XOF");
        assertThat(outbound.path("paymentReference").asText()).isEqualTo(number);
        assertThat(outbound.path("customerObject").path("name").asText()).isEqualTo("Awa Ndiaye");
        assertThat(outbound.path("customerObject").path("phone").asText())
                .isEqualTo("+221771234567");
        assertThat(orderStatus()).isEqualTo("PENDING_PAYMENT_INTEGRATION");
        assertThat(count("direct_order_verified_settlements")).isZero();
        assertThat(count("payments")).isEqualTo(1);
    }

    @Test
    void authenticatedWebhookConfirmsExactlyOnceAndBlocksMerchantCancellation() throws Exception {
        queueCharge();
        UUID payment =
                UUID.fromString(
                        body(initiate(recovery, CHECKOUT_KEY)
                                        .andExpect(status().isOk())
                                        .andReturn())
                                .path("id")
                                .asText());
        String payload = successPayload(UUID.randomUUID().toString(), 10300);
        webhook(payload, "fixture-webhook-secret").andExpect(status().isOk());
        await(() -> "PAYMENT_CONFIRMED".equals(orderStatus()));
        // Canonical timestamps must survive the Java -> PostgreSQL -> event boundary
        // exactly. Millisecond clocks alone would not catch this regression.
        var paymentConfirmedAt =
                jdbc.queryForObject(
                                "select confirmed_at from payments where id=?",
                                java.sql.Timestamp.class,
                                payment)
                        .toInstant();
        var orderConfirmedAt =
                jdbc.queryForObject(
                                "select payment_confirmed_at from direct_orders where id=?",
                                java.sql.Timestamp.class,
                                order)
                        .toInstant();
        assertThat(orderConfirmedAt).isEqualTo(paymentConfirmedAt);
        assertThat(paymentConfirmedAt.getNano() % 1000).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select settled_payment_id from payment_requests where direct_order_id=?",
                                UUID.class,
                                order))
                .isEqualTo(payment);
        assertThat(count("direct_order_verified_settlements")).isEqualTo(1);
        financial(recovery)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financialStatus").value("SETTLEMENT_VERIFIED"))
                .andExpect(jsonPath("$.paymentAvailable").value(false));
        webhook(payload, "fixture-webhook-secret").andExpect(status().isOk());
        assertThat(count("direct_order_verified_settlements")).isEqualTo(1);
        mvc.perform(
                        post("/api/organizations/" + org + "/direct-orders/" + order + "/cancel")
                                .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isConflict());
    }

    @Test
    void forgedWebhookAndAmountNeverMakeOrderPaid() throws Exception {
        queueCharge();
        initiate(recovery, CHECKOUT_KEY).andExpect(status().isOk());
        String payload = successPayload(UUID.randomUUID().toString(), 10300);
        webhook(payload, "wrong-secret").andExpect(status().isOk());
        assertThat(orderStatus()).isEqualTo("PENDING_PAYMENT_INTEGRATION");
        assertThat(count("direct_order_verified_settlements")).isZero();

        webhook(successPayload(UUID.randomUUID().toString(), 10301), "fixture-webhook-secret")
                .andExpect(status().isOk());
        assertThat(orderStatus()).isEqualTo("PENDING_PAYMENT_INTEGRATION");
        assertThat(count("direct_order_verified_settlements")).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select settled_payment_id from payment_requests where direct_order_id=?",
                                UUID.class,
                                order))
                .isNull();
    }

    @Test
    void cancellationAndRevocationCannotChargeWithAnOldPrivateLink() throws Exception {
        String cancelled = "/api/organizations/" + org + "/direct-orders/" + order + "/cancel";
        mvc.perform(post(cancelled).header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk());
        queueCharge();
        initiate(recovery, CHECKOUT_KEY).andExpect(status().isConflict());
        assertThat(bictorys.requests).isEmpty();
        assertThat(count("payments")).isZero();

        // A previously valid order token also cannot start a charge after its rotation.
        // Restore the fixture in a new test transaction instead of resurrecting cancelled data.
    }

    @Test
    void simultaneousMerchantCancellationAndCustomerInitiationNeverChargeCancelledOrder()
            throws Exception {
        queueCharge();
        String cancelUrl = "/api/organizations/" + org + "/direct-orders/" + order + "/cancel";
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> customer =
                    executor.submit(
                            () -> {
                                start.await();
                                return initiate(recovery, "direct-race-sandbox-key-0001")
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            Future<Integer> merchant =
                    executor.submit(
                            () -> {
                                start.await();
                                return mvc.perform(
                                                post(cancelUrl)
                                                        .header(
                                                                "Authorization",
                                                                "Bearer " + bearer))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            start.countDown();
            int customerStatus = customer.get(30, TimeUnit.SECONDS);
            int merchantStatus = merchant.get(30, TimeUnit.SECONDS);
            assertThat(customerStatus).isIn(200, 409);
            assertThat(merchantStatus).isIn(200, 409);
            assertThat(customerStatus == 200 && merchantStatus == 200).isFalse();

            if ("CANCELLED".equals(orderStatus())) {
                assertThat(merchantStatus).isEqualTo(200);
                assertThat(customerStatus).isEqualTo(409);
                assertThat(count("payments")).isZero();
                assertThat(bictorys.requests).isEmpty();
            } else {
                assertThat(orderStatus()).isEqualTo("PENDING_PAYMENT_INTEGRATION");
                assertThat(customerStatus).isEqualTo(200);
                assertThat(merchantStatus).isEqualTo(409);
                assertThat(count("payments")).isEqualTo(1);
                assertThat(bictorys.requests).hasSize(1);
            }
        }
    }

    @Test
    void rotatedCapabilityIsRecheckedBeforeProviderClaim() throws Exception {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String fresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update(
                "update direct_orders set recovery_token_hash=?,version=version+1 where id=?",
                DirectAccessTokens.hashForCreate(fresh),
                order);
        queueCharge();
        initiate(recovery, CHECKOUT_KEY).andExpect(status().isNotFound());
        assertThat(bictorys.requests).isEmpty();
        initiate(fresh, CHECKOUT_KEY).andExpect(status().isOk());
        assertThat(bictorys.requests).hasSize(1);
    }
}
