package sn.groupia;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "groupia.subscription.enforcement-enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class OrderingIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager transactions;
    private static String fixtureHash;
    private UUID actor;
    private UUID org;
    private UUID variant;
    private UUID productId;
    private String jwt;
    private JsonNode campaign;
    private String key;
    private final String phone = "77 123 45 67";

    @BeforeEach
    void setup() throws Exception {
        TestEventDrain.awaitIdle(jdbc);
        jdbc.execute(
                "TRUNCATE TABLE outgoing_provider_operations,order_fulfillment_events, shipping_calculations, shipping_measurements, shipping_assessments, customer_access_sessions, otp_challenges, order_access_links, customer_access_limits, shipment_facts, shipments, supplier_order_anomalies, supplier_orders, payment_webhook_events, payments, payment_requests, order_items, orders, customers, grouping_changes, grouping_offers, grouping_campaigns, product_variants, products, organization_members, organizations, user_accounts, event_publication CASCADE");
        if (fixtureHash == null) {
            fixtureHash = passwords.encode("Commande fixture 2026!");
        }
        actor = UUID.randomUUID();
        jdbc.update(
                "insert into user_accounts (id,phone_number,password_hash,status,created_at,updated_at) values (?, '+221771234567', ?, 'ACTIVE', current_timestamp, current_timestamp)",
                actor,
                fixtureHash);
        jwt = token(actor);
        org =
                UUID.fromString(
                        read(auth(post("/api/organizations"), Map.of("name", "Organisateur"))
                                        .andExpect(status().isCreated())
                                        .andReturn())
                                .get("id")
                                .asText());
        var product =
                read(
                        auth(
                                        post("/api/organizations/" + org + "/products"),
                                        Map.of(
                                                "name",
                                                "Chaussures",
                                                "description",
                                                "Description historique"))
                                .andExpect(status().isCreated())
                                .andReturn());
        productId = UUID.fromString(product.get("id").asText());
        variant = UUID.fromString(product.get("variants").get(0).get("id").asText());
        jdbc.update(
                "update product_variants set sku = 'SHOE-01', estimated_unit_weight_kg = 0.3335, estimated_unit_volume_m3 = 0.000125 where id = ?",
                variant);
        campaign = campaign(org, variant, "FIXED", "AFTER_FINAL_ASSESSMENT", "1000");
        key = UUID.randomUUID().toString();
    }

    @Test
    void placesAnonymousOrderWithAuthoritativeSnapshotsAndNormalizedContact() throws Exception {
        var response =
                checkout(key, input())
                        .andExpect(status().isCreated())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                        .andExpect(jsonPath("$.fulfillmentStatus").value("NOT_READY"))
                        .andExpect(jsonPath("$.goodsSubtotal").value(29000))
                        .andExpect(jsonPath("$.platformFeeAmount").value(300))
                        .andExpect(jsonPath("$.estimatedShippingAmount").value(1000))
                        .andExpect(jsonPath("$.initialShippingChargeAmount").value(0))
                        .andExpect(jsonPath("$.initialAmountDue").value(29300))
                        .andExpect(jsonPath("$.currency").value("XOF"))
                        .andExpect(jsonPath("$.items[0].skuSnapshot").value("SHOE-01"))
                        .andExpect(jsonPath("$.items[0].productNameSnapshot").value("Chaussures"))
                        .andExpect(
                                jsonPath("$.items[0].productDescriptionSnapshot")
                                        .value("Description historique"))
                        .andExpect(jsonPath("$.items[0].unitPriceSnapshot").value(14500))
                        .andExpect(jsonPath("$.items[0].quantity").value(2))
                        .andExpect(jsonPath("$.organizationId").doesNotExist());
        var order = read(response.andReturn());
        assertThat(order.get("orderNumber").asText()).matches("GRP-[0-9]{12,19}");
        assertThat(Instant.parse(order.get("paymentDeadline").asText()))
                .isEqualTo(Instant.parse(order.get("placedAt").asText()).plusSeconds(1800));
        assertThat(count("customers")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select phone_e164 from customers", String.class))
                .isEqualTo("+221771234567");
        assertThat(
                        jdbc.queryForObject(
                                "select customer_contact_snapshot->>'firstName' from orders",
                                String.class))
                .isEqualTo("Awa");
        assertThat(
                        jdbc.queryForObject(
                                "select platform_fee_rule_snapshot->>'amount' from orders",
                                String.class))
                .isEqualTo("300");
        assertThat(jdbc.queryForObject("select product_id_snapshot from order_items", UUID.class))
                .isEqualTo(productId);
        assertThat(
                        jdbc.queryForObject(
                                "select product_variant_id_snapshot from order_items", UUID.class))
                .isEqualTo(variant);
        assertThat(jdbc.queryForObject("select tracking_token_hash from orders", String.class))
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(order.get("trackingToken").asText());
        assertThat(order.get("trackingToken").asText()).matches("[A-Za-z0-9_-]{43}");
        assertThat(jdbc.queryForObject("select row_to_json(o)::text from orders o", String.class))
                .doesNotContain(order.get("trackingToken").asText());
    }

    @Test
    void openCampaignCheckoutRemainsAvailableAfterTheOrganizationTrialExpires() throws Exception {
        jdbc.update(
                """
                update organization_subscriptions
                   set trial_started_at=current_timestamp - interval '2 seconds',
                       trial_ends_at=current_timestamp - interval '1 second',
                       status='TRIALING'
                 where organization_id=?
                """,
                org);
        checkout(key, input())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    void newCheckoutNeverMergesPhoneAndNumbersAreUnique() throws Exception {
        var first = read(checkout(key, input()).andExpect(status().isCreated()).andReturn());
        var second =
                read(
                        checkout(UUID.randomUUID().toString(), input())
                                .andExpect(status().isCreated())
                                .andReturn());
        assertThat(count("customers")).isEqualTo(2);
        assertThat(count("orders")).isEqualTo(2);
        assertThat(first.get("orderNumber").asText())
                .isNotEqualTo(second.get("orderNumber").asText());
        assertThat(first.get("trackingToken").asText())
                .isNotEqualTo(second.get("trackingToken").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "CLOSED", "EXPIRED", "CANCELLED", "ARCHIVED"})
    void rejectsNonOrderableCampaignWithoutContactOrOrder(String state) throws Exception {
        switch (state) {
            case "DRAFT" ->
                    jdbc.update("update grouping_campaigns set status = 'DRAFT', opened_at = null");
            case "CLOSED" ->
                    jdbc.update(
                            "update grouping_campaigns set status = 'CLOSED', closed_at = greatest(current_timestamp, opened_at)");
            case "EXPIRED" ->
                    jdbc.update(
                            "update grouping_campaigns set closes_at = current_timestamp - interval '1 second'");
            case "CANCELLED" ->
                    jdbc.update(
                            "update grouping_campaigns set status = 'CANCELLED', cancelled_at = greatest(current_timestamp, created_at), cancellation_reason = 'Test'");
            case "ARCHIVED" -> jdbc.update("update organizations set status = 'ARCHIVED'");
        }
        checkout(key, input()).andExpect(status().isConflict());
        assertThat(count("orders")).isZero();
        assertThat(count("customers")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"offer", "variant", "product"})
    void rejectsUnavailableCatalogueOrOffer(String target) throws Exception {
        if (target.equals("offer")) {
            jdbc.update("update grouping_offers set active = false");
        }
        if (target.equals("variant")) {
            jdbc.update("update product_variants set active = false");
        }
        if (target.equals("product")) {
            jdbc.update("update products set status = 'ARCHIVED'");
        }
        checkout(key, input())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUPING_OFFER_NOT_AVAILABLE"));
        assertThat(count("customers")).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1000001})
    void rejectsInvalidQuantity(int quantity) throws Exception {
        checkout(key, request(List.of(line(offerId(), quantity, "14500", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_QUANTITY"));
        assertThat(count("orders")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"price", "revision"})
    void rejectsStaleConsentAndReturnsOnlySafeCurrentPrice(String stale) throws Exception {
        checkout(
                        key,
                        request(
                                List.of(
                                        line(
                                                offerId(),
                                                2,
                                                stale.equals("price") ? "14000" : "14500",
                                                stale.equals("revision") ? 1 : 0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUPING_OFFER_PRICE_CHANGED"))
                .andExpect(jsonPath("$.details.unitPrice").value(14500))
                .andExpect(jsonPath("$.details.priceRevision").value(0))
                .andExpect(jsonPath("$.details.organizationId").doesNotExist());
        assertThat(count("orders")).isZero();
        assertThat(count("customers")).isZero();
    }

    @Test
    void samePriceAfterTwoChangesStillRequiresNewRevision() throws Exception {
        changePrice(campaign, "15000").andExpect(status().isOk());
        var refreshed = read(auth(get(campaignPath())).andExpect(status().isOk()).andReturn());
        changePrice(refreshed, "14500").andExpect(status().isOk());
        checkout(key, input())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUPING_OFFER_PRICE_CHANGED"));
    }

    @Test
    void rejectsDuplicateLinesAndForeignCampaignOffer() throws Exception {
        var item = line(offerId(), 1, "14500", 0);
        checkout(key, request(List.of(item, item)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ORDER_OFFER"));
        var other = campaign(org, variant, "FIXED", "AFTER_FINAL_ASSESSMENT", "0");
        checkout(
                        key,
                        request(
                                List.of(
                                        line(
                                                other.get("offers").get(0).get("id").asText(),
                                                1,
                                                "14500",
                                                0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUPING_OFFER_NOT_AVAILABLE"));
        assertThat(count("orders")).isZero();
    }

    @Test
    void rejectsOtherOrganizationOffer() throws Exception {
        var otherOrg =
                UUID.fromString(
                        read(auth(post("/api/organizations"), Map.of("name", "Autre"))
                                        .andExpect(status().isCreated())
                                        .andReturn())
                                .get("id")
                                .asText());
        var product =
                read(
                        auth(
                                        post("/api/organizations/" + otherOrg + "/products"),
                                        Map.of("name", "Autre"))
                                .andExpect(status().isCreated())
                                .andReturn());
        var other =
                campaign(
                        otherOrg,
                        UUID.fromString(product.get("variants").get(0).get("id").asText()),
                        "FIXED",
                        "AFTER_FINAL_ASSESSMENT",
                        "0");
        checkout(
                        key,
                        request(
                                List.of(
                                        line(
                                                other.get("offers").get(0).get("id").asText(),
                                                1,
                                                "14500",
                                                0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUPING_OFFER_NOT_AVAILABLE"));
    }

    @ParameterizedTest
    @CsvSource({"PER_KG,1000,667", "PER_CBM,1000000,250", "PER_ITEM,125.25,251", "FIXED,125,125"})
    void computesShippingFromPreciseCatalogueInputs(String method, String rate, int expected)
            throws Exception {
        campaign = campaign(org, variant, method, "ESTIMATED_AT_CHECKOUT", rate);
        checkout(key, input())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estimatedShippingAmount").value(expected))
                .andExpect(jsonPath("$.initialShippingChargeAmount").value(expected))
                .andExpect(jsonPath("$.initialAmountDue").value(29300 + expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PER_KG", "PER_CBM"})
    void missingEstimateFailsInsteadOfInventingAWeight(String method) throws Exception {
        campaign = campaign(org, variant, method, "ESTIMATED_AT_CHECKOUT", "1000");
        jdbc.update(
                "update product_variants set estimated_unit_weight_kg = null, estimated_unit_volume_m3 = null");
        checkout(key, input())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIPPING_ESTIMATE_UNAVAILABLE"));
        assertThat(count("orders")).isZero();
        assertThat(count("customers")).isZero();
    }

    @Test
    void unavailableInformativeEstimateIsNullAndNotCollected() throws Exception {
        campaign = campaign(org, variant, "PER_KG", "AFTER_FINAL_ASSESSMENT", "1000");
        jdbc.update("update product_variants set estimated_unit_weight_kg = null");
        checkout(key, input())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.initialShippingChargeAmount").value(0))
                .andExpect(jsonPath("$.initialAmountDue").value(29300));
        assertThat(
                        jdbc.queryForObject(
                                "select estimated_shipping_amount from orders", BigDecimal.class))
                .isNull();
    }

    @Test
    void preservesCatalogueContactAndShippingSnapshotsAfterEdits() throws Exception {
        var placed = read(checkout(key, input()).andExpect(status().isCreated()).andReturn());
        jdbc.update("update products set name = 'Nouveau', description = 'Modifiée'");
        jdbc.update("update product_variants set sku = 'CHANGED', estimated_unit_weight_kg = 9");
        jdbc.update("update customers set first_name = 'Autre', phone_e164 = '+221781234567'");
        changePrice(campaign, "16000").andExpect(status().isOk());
        track(placed.get("trackingToken").asText())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goodsSubtotal").value(29000))
                .andExpect(jsonPath("$.platformFeeAmount").value(300))
                .andExpect(jsonPath("$.items[0].productNameSnapshot").value("Chaussures"))
                .andExpect(jsonPath("$.items[0].skuSnapshot").value("SHOE-01"));
        assertThat(
                        jdbc.queryForObject(
                                "select customer_contact_snapshot->>'firstName' from orders",
                                String.class))
                .isEqualTo("Awa");
        assertThat(
                        jdbc.queryForObject(
                                "select shipping_terms_snapshot->>'shippingRate' from orders",
                                String.class))
                .isEqualTo("1000");
    }

    @Test
    void replayReturnsSameAcknowledgementWithoutSecretOrPrivateDataEvenAfterClose()
            throws Exception {
        var placed =
                read(
                        checkout("  " + key + "  ", input())
                                .andExpect(status().isCreated())
                                .andReturn());
        auth(post(campaignPath() + "/close"), Map.of("version", campaign.get("version").asLong()))
                .andExpect(status().isOk());
        var replay = read(checkout(key, input()).andExpect(status().isOk()).andReturn());
        assertThat(replay.get("orderNumber").asText())
                .isEqualTo(placed.get("orderNumber").asText());
        assertThat(replay.get("replayed").asBoolean()).isTrue();
        assertThat(replay.size()).isEqualTo(2);
        assertThat(count("customers")).isEqualTo(1);
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void normalizedPhoneAndNumericalPriceHaveSameFingerprint() throws Exception {
        checkout(key, input()).andExpect(status().isCreated());
        var normalized = request(List.of(line(offerId(), 2, "14500.00", 0)));
        normalized.put(
                "contact",
                Map.of(
                        "firstName",
                        "Awa",
                        "lastName",
                        "Diop",
                        "phone",
                        "+221771234567",
                        "email",
                        "awa@example.test"));
        checkout(key, normalized)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void reusedKeyWithDifferentContentConflicts() throws Exception {
        checkout(key, input()).andExpect(status().isCreated());
        checkout(key, request(List.of(line(offerId(), 3, "14500", 0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(count("customers")).isEqualTo(1);
    }

    @Test
    void sameOrganizationKeyCannotBeReusedAcrossCampaigns() throws Exception {
        checkout(key, input()).andExpect(status().isCreated());
        campaign = campaign(org, variant, "FIXED", "AFTER_FINAL_ASSESSMENT", "0");
        checkout(key, input())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(count("orders")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "abcdefghijklmnop!", "spaces are forbidden"})
    void invalidKeysDoNotWrite(String candidate) throws Exception {
        checkout(candidate, input()).andExpect(status().isBadRequest());
        assertThat(count("orders")).isZero();
    }

    @Test
    void missingKeyAndInjectedOrganizationOrTotalAreRejected() throws Exception {
        mvc.perform(body(post(checkoutPath()), input()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        for (String field :
                List.of(
                        "organizationId",
                        "goodsSubtotal",
                        "initialAmountDue",
                        "status",
                        "trackingToken")) {
            var injected = input();
            injected.put(field, "forged");
            checkout(key, injected).andExpect(status().isBadRequest());
        }
        assertThat(count("customers")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"phone", "email", "firstName", "lastName"})
    void invalidContactsNeverCreateACustomer(String field) throws Exception {
        var contact =
                new HashMap<String, Object>(
                        Map.of(
                                "firstName",
                                "Awa",
                                "lastName",
                                "Diop",
                                "phone",
                                phone,
                                "email",
                                "awa@example.test"));
        contact.put(field, field.equals("email") ? "invalid" : "");
        var request = input();
        request.put("contact", contact);
        checkout(key, request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CUSTOMER_CONTACT"));
        assertThat(count("customers")).isZero();
    }

    @Test
    void usesOrganizationCountryForNationalPhone() throws Exception {
        assertThat(
                        jdbc.queryForObject(
                                "select country_code from organizations where id = ?",
                                String.class,
                                org))
                .isEqualTo("SN");
        var request = input();
        request.put(
                "contact", Map.of("firstName", "Awa", "lastName", "Diop", "phone", "78 123 45 67"));
        checkout(key, request).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select phone_e164 from customers", String.class))
                .isEqualTo("+221781234567");
    }

    @Test
    void trackingRequiresSecretAndNeverLeaksHashOrInternalData() throws Exception {
        var placed = read(checkout(key, input()).andExpect(status().isCreated()).andReturn());
        track(placed.get("trackingToken").asText())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value(placed.get("orderNumber").asText()))
                .andExpect(jsonPath("$.trackingToken").doesNotExist())
                .andExpect(jsonPath("$.trackingTokenHash").doesNotExist())
                .andExpect(jsonPath("$.customerContactSnapshot").doesNotExist())
                .andExpect(jsonPath("$.organizationId").doesNotExist());
        for (String value :
                List.of(
                        placed.get("orderNumber").asText(),
                        key,
                        "+221771234567",
                        UUID.randomUUID().toString(),
                        "a".repeat(43))) {
            track(value)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ORDER_TRACKING_INVALID"));
        }
        mvc.perform(get("/api/public/orders/track")).andExpect(status().isNotFound());
        mvc.perform(get("/api/organizations/" + org + "/orders"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "revoked"})
    void trackingExpiryAndRevocationAreEnforced(String reason) throws Exception {
        var placed = read(checkout(key, input()).andExpect(status().isCreated()).andReturn());
        if (reason.equals("expired")) {
            jdbc.update(
                    "update orders set placed_at = current_timestamp - interval '2 days', tracking_token_expires_at = current_timestamp - interval '1 day'");
        } else {
            jdbc.update("update orders set tracking_token_revoked_at = current_timestamp");
        }
        track(placed.get("trackingToken").asText())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_TRACKING_INVALID"));
    }

    @Test
    void rateLimitsTrackingAttemptsWithoutTrustingForwardedAddress() throws Exception {
        for (int i = 0; i < 60; i++) {
            mvc.perform(
                            get("/api/public/orders/track")
                                    .with(
                                            r -> {
                                                r.setRemoteAddr("192.0.2.45");
                                                return r;
                                            })
                                    .header("X-Forwarded-For", "198.51.100." + i)
                                    .header("X-Order-Tracking-Token", "invalid"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(
                        get("/api/public/orders/track")
                                .with(
                                        r -> {
                                            r.setRemoteAddr("192.0.2.45");
                                            return r;
                                        }))
                .andExpect(status().isTooManyRequests());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "MANAGER", "OPERATOR", "VIEWER"})
    void allReadRolesCanReadOnlyTheirOrganizations(String role) throws Exception {
        checkout(key, input()).andExpect(status().isCreated());
        jdbc.update(
                "update organization_members set role = ? where organization_id = ?", role, org);
        String id = jdbc.queryForObject("select id from orders", UUID.class).toString();
        auth(get("/api/organizations/" + org + "/orders/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerContactSnapshot.phoneE164").value("+221771234567"))
                .andExpect(jsonPath("$.order.trackingToken").doesNotExist());
        auth(get("/api/organizations/" + UUID.randomUUID() + "/orders/" + id))
                .andExpect(status().isForbidden());
        auth(get("/api/organizations/" + org + "/orders/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "user", "organization"})
    void revokedOrInactiveActorsCannotRead(String target) throws Exception {
        checkout(key, input()).andExpect(status().isCreated());
        if (target.equals("member")) {
            jdbc.update(
                    "update organization_members set status = 'REVOKED', revoked_at = current_timestamp");
        }
        if (target.equals("user")) {
            jdbc.update("update user_accounts set status = 'DISABLED'");
        }
        if (target.equals("organization")) {
            jdbc.update("update organizations set status = 'ARCHIVED'");
        }
        assertThat(
                        auth(get("/api/organizations/" + org + "/orders"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isIn(401, 403, 404);
    }

    @Test
    void paginatesAndFiltersWithoutLoadingOrExposingSecrets() throws Exception {
        checkout(key, input()).andExpect(status().isCreated());
        checkout(UUID.randomUUID().toString(), input()).andExpect(status().isCreated());
        auth(get(
                        "/api/organizations/"
                                + org
                                + "/orders?size=1&page=1&campaignId="
                                + campaign.get("id").asText()
                                + "&status=PENDING_PAYMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].items").doesNotExist());
        auth(get("/api/organizations/" + org + "/orders?status=CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        auth(get("/api/organizations/" + org + "/orders?campaignId=" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        auth(get("/api/organizations/" + org + "/orders?size=101"))
                .andExpect(status().isBadRequest());
        auth(get("/api/organizations/" + org + "/orders?status=PAID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void simultaneousIdenticalCheckoutsCreateExactlyOneCustomerAndOrder() throws Exception {
        var results =
                race(
                        () -> checkout(key, input()).andReturn(),
                        () -> checkout(key, input()).andReturn());
        assertThat(results.stream().map(r -> r.getResponse().getStatus()))
                .containsExactlyInAnyOrder(201, 200);
        assertThat(read(results.get(0)).get("orderNumber").asText())
                .isEqualTo(read(results.get(1)).get("orderNumber").asText());
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("customers")).isEqualTo(1);
        assertThat(
                        results.stream()
                                .filter(
                                        r ->
                                                new String(
                                                                r.getResponse()
                                                                        .getContentAsByteArray(),
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)
                                                        .contains("trackingToken")))
                .hasSize(1);
    }

    @Test
    void simultaneousDifferentPayloadsUsingSameKeyHaveOneWinner() throws Exception {
        var results =
                race(
                        () -> checkout(key, input()).andReturn(),
                        () ->
                                checkout(key, request(List.of(line(offerId(), 3, "14500", 0))))
                                        .andReturn());
        assertThat(results.stream().map(r -> r.getResponse().getStatus()))
                .containsExactlyInAnyOrder(201, 409);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("customers")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"close", "price"})
    void committedWriterBeforeAdmissionRejectsCheckout(String operation) throws Exception {
        coordinatedRace(operation, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"close", "price"})
    void admittedCheckoutCommitsBeforeWaitingWriter(String operation) throws Exception {
        coordinatedRace(operation, false);
    }

    private void coordinatedRace(String operation, boolean writerFirst) throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var tx = new TransactionTemplate(transactions);
        Callable<MvcResult> writer =
                () ->
                        operation.equals("close")
                                ? auth(
                                                post(campaignPath() + "/close"),
                                                Map.of("version", campaign.get("version").asLong()))
                                        .andReturn()
                                : changePrice(campaign, "15000").andReturn();
        Callable<MvcResult> buyer = () -> checkout(key, input()).andReturn();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () ->
                                    tx.execute(
                                            status -> {
                                                try {
                                                    var result =
                                                            (writerFirst ? writer : buyer).call();
                                                    assertThat(result.getResponse().getStatus())
                                                            .isEqualTo(writerFirst ? 200 : 201);
                                                    held.countDown();
                                                    assertThat(release.await(10, TimeUnit.SECONDS))
                                                            .isTrue();
                                                    return result;
                                                } catch (Exception ex) {
                                                    throw new RuntimeException(ex);
                                                }
                                            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(writerFirst ? buyer : writer);
            try {
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waiting = false;
                while (System.nanoTime() < until) {
                    waiting =
                            Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "select exists(select 1 from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock' and query like '%grouping_campaigns%')",
                                            Boolean.class));
                    if (waiting) {
                        break;
                    }
                    Thread.sleep(25);
                }
                assertThat(waiting).as("competing transaction waits for campaign lock").isTrue();
                assertThat(second.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            first.get(15, TimeUnit.SECONDS);
            var result = second.get(15, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(writerFirst ? 409 : 200);
            if (writerFirst) {
                assertThat(read(result).get("code").asText())
                        .isEqualTo(
                                operation.equals("close")
                                        ? "GROUPING_CLOSED"
                                        : "GROUPING_OFFER_PRICE_CHANGED");
            }
        } finally {
            release.countDown();
        }
        assertThat(count("orders")).isEqualTo(writerFirst ? 0 : 1);
        assertThat(count("customers")).isEqualTo(writerFirst ? 0 : 1);
        if (!writerFirst) {
            assertThat(
                            jdbc.queryForObject(
                                    "select unit_price_snapshot from order_items",
                                    BigDecimal.class))
                    .isEqualByComparingTo("14500");
        }
    }

    @Test
    void postgresEnforcesFinancialAndOwnershipConstraints() throws Exception {
        checkout(key, input()).andExpect(status().isCreated());
        for (String sql :
                List.of(
                        "update order_items set quantity=0",
                        "update order_items set line_number=0",
                        "update order_items set line_total=-1",
                        "update order_items set unit_price_snapshot=0.5",
                        "update order_items set organization_id=gen_random_uuid()",
                        "update order_items set campaign_id=gen_random_uuid()",
                        "update orders set initial_amount_due=1",
                        "update orders set goods_subtotal='NaN'",
                        "update orders set currency='EUR'",
                        "update orders set tracking_token_hash='raw-token'",
                        "update orders set initial_shipping_charge_amount=10, initial_amount_due=29310",
                        "delete from grouping_campaigns",
                        "delete from grouping_offers",
                        "delete from customers",
                        "delete from orders",
                        "delete from product_variants")) {
            assertThatThrownBy(() -> jdbc.update(sql))
                    .as(sql)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        checkout(UUID.randomUUID().toString(), input()).andExpect(status().isCreated());
        String number = jdbc.queryForObject("select min(order_number) from orders", String.class);
        assertThatThrownBy(() -> jdbc.update("update orders set order_number=?", number))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update orders set checkout_idempotency_key=?", key))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insertionFailureRollsBackCustomerOrderAndLines() throws Exception {
        jdbc.execute("alter table order_items add constraint test_order_failure check(false)");
        try {
            checkout(key, input())
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.trace").doesNotExist());
            assertThat(count("customers")).isZero();
            assertThat(count("orders")).isZero();
            assertThat(count("order_items")).isZero();
        } finally {
            jdbc.execute("alter table order_items drop constraint test_order_failure");
        }
        checkout(key, input()).andExpect(status().isCreated());
    }

    @Test
    void multipleLinesSumAuthoritativeTotalsAndCanonicalizeOrder() throws Exception {
        var product =
                read(
                        auth(
                                        post("/api/organizations/" + org + "/products"),
                                        Map.of(
                                                "name",
                                                "Sac",
                                                "variants",
                                                List.of(
                                                        Map.of(
                                                                "attributes",
                                                                Map.of("color", "black"),
                                                                "saleUnit",
                                                                "UNIT"))))
                                .andExpect(status().isCreated())
                                .andReturn());
        var selected = product.get("variants").get(0).get("id").asText();
        String originalOffer = offerId();
        campaign =
                read(
                        auth(
                                        post(campaignPath() + "/offers"),
                                        Map.of(
                                                "campaignVersion",
                                                campaign.get("version").asLong(),
                                                "productVariantId",
                                                selected,
                                                "unitPrice",
                                                500))
                                .andExpect(status().isOk())
                                .andReturn());
        String secondOffer = campaign.get("offers").get(1).get("id").asText();
        var firstLine = line(originalOffer, 2, "14500", 0);
        var secondLine = line(secondOffer, 3, "500", 0);
        checkout(key, request(List.of(secondLine, firstLine)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.goodsSubtotal").value(30500))
                .andExpect(jsonPath("$.initialAmountDue").value(30800))
                .andExpect(jsonPath("$.items", hasSize(2)));
        checkout(key, request(List.of(firstLine, secondLine))).andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "select variant_attributes_snapshot->>'color' from order_items where product_name_snapshot='Sac'",
                                String.class))
                .isEqualTo("black");
        assertThat(count("order_items")).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("update order_items set line_number=1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void evenMemberOfTwoOrganizationsCannotReadOrderThroughWrongOrganization() throws Exception {
        checkout(key, input()).andExpect(status().isCreated());
        var otherOrg =
                read(auth(post("/api/organizations"), Map.of("name", "Autre"))
                                .andExpect(status().isCreated())
                                .andReturn())
                        .get("id")
                        .asText();
        var id = jdbc.queryForObject("select id from orders", UUID.class);
        auth(get("/api/organizations/" + otherOrg + "/orders/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        auth(get("/api/organizations/" + otherOrg + "/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void concurrentKeyAcrossTwoCampaignsIsAlsoSerialized() throws Exception {
        var other = campaign(org, variant, "FIXED", "AFTER_FINAL_ASSESSMENT", "0");
        var otherInput =
                request(
                        List.of(
                                line(
                                        other.get("offers").get(0).get("id").asText(),
                                        2,
                                        "14500",
                                        0)));
        var results =
                race(
                        () -> checkout(key, input()).andReturn(),
                        () ->
                                mvc.perform(
                                                body(
                                                                post(
                                                                        "/api/public/groupings/"
                                                                                + other.get(
                                                                                                "publicIdentifier")
                                                                                        .asText()
                                                                                + "/checkout"),
                                                                otherInput)
                                                        .header("Idempotency-Key", key))
                                        .andReturn());
        assertThat(results.stream().map(r -> r.getResponse().getStatus()))
                .containsExactlyInAnyOrder(201, 409);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("customers")).isEqualTo(1);
    }

    private List<MvcResult> race(Callable<MvcResult> a, Callable<MvcResult> b) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () -> {
                                start.await();
                                return a.call();
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                return b.call();
                            });
            start.countDown();
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
    }

    private Map<String, Object> input() {
        return request(List.of(line(offerId(), 2, "14500", 0)));
    }

    private Map<String, Object> request(List<?> lines) {
        var result = new LinkedHashMap<String, Object>();
        result.put(
                "contact",
                Map.of(
                        "firstName",
                        " Awa ",
                        "lastName",
                        "Diop",
                        "phone",
                        phone,
                        "email",
                        "awa@example.test"));
        result.put("lines", lines);
        return result;
    }

    private Map<String, Object> line(String id, int quantity, String price, long revision) {
        return Map.of(
                "offerId",
                id,
                "quantity",
                quantity,
                "expectedUnitPrice",
                new BigDecimal(price),
                "expectedPriceRevision",
                revision);
    }

    private JsonNode campaign(UUID owner, UUID selected, String method, String mode, String rate)
            throws Exception {
        String base = "/api/organizations/" + owner + "/grouping-campaigns";
        var c =
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
                                                        method,
                                                        "shippingCollectionMode",
                                                        mode,
                                                        "shippingRate",
                                                        new BigDecimal(rate))))
                                .andExpect(status().isCreated())
                                .andReturn());
        c =
                read(
                        auth(
                                        post(base + "/" + c.get("id").asText() + "/offers"),
                                        Map.of(
                                                "campaignVersion",
                                                c.get("version").asLong(),
                                                "productVariantId",
                                                selected,
                                                "unitPrice",
                                                14500,
                                                "commercialLabel",
                                                "Lot spécial"))
                                .andExpect(status().isOk())
                                .andReturn());
        return read(
                auth(
                                post(base + "/" + c.get("id").asText() + "/open"),
                                Map.of("version", c.get("version").asLong()))
                        .andExpect(status().isOk())
                        .andReturn());
    }

    private ResultActions changePrice(JsonNode c, String price) throws Exception {
        return auth(
                put(
                        "/api/organizations/"
                                + org
                                + "/grouping-campaigns/"
                                + c.get("id").asText()
                                + "/offers/"
                                + c.get("offers").get(0).get("id").asText()),
                Map.of(
                        "campaignVersion",
                        c.get("version").asLong(),
                        "version",
                        c.get("offers").get(0).get("version").asLong(),
                        "unitPrice",
                        new BigDecimal(price)));
    }

    private String campaignPath() {
        return "/api/organizations/" + org + "/grouping-campaigns/" + campaign.get("id").asText();
    }

    private String checkoutPath() {
        return "/api/public/groupings/" + campaign.get("publicIdentifier").asText() + "/checkout";
    }

    private String offerId() {
        return campaign.get("offers").get(0).get("id").asText();
    }

    private ResultActions checkout(String key, Object input) throws Exception {
        return mvc.perform(body(post(checkoutPath()), input).header("Idempotency-Key", key));
    }

    private ResultActions track(String secret) throws Exception {
        return mvc.perform(
                get("/api/public/orders/track").header("X-Order-Tracking-Token", secret));
    }

    private ResultActions auth(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + jwt));
    }

    private ResultActions auth(MockHttpServletRequestBuilder request, Object value)
            throws Exception {
        return auth(body(request, value));
    }

    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object value)
            throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(value));
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private String token(UUID id) {
        var claims =
                JwtClaimsSet.builder()
                        .issuer("https://groupia.test")
                        .audience(List.of("groupia-api-test"))
                        .subject(id.toString())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(600))
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
