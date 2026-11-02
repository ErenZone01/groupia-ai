package sn.groupia;

import static org.assertj.core.api.Assertions.*;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "groupia.direct-sales.checkout.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DirectStorefrontIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder encoder;

    private String bearer;
    private UUID organizationId;

    @BeforeEach
    void setup() throws Exception {
        TestEventDrain.awaitIdle(jdbc);
        jdbc.execute(
                "TRUNCATE TABLE outgoing_provider_operations,order_fulfillment_events,"
                        + " shipping_calculations, shipping_measurements, shipping_assessments,"
                        + " customer_access_sessions, otp_challenges, order_access_links,"
                        + " customer_access_limits, shipment_facts, shipments,"
                        + " supplier_order_anomalies, supplier_orders, payment_webhook_events,"
                        + " payments, payment_requests, order_items, orders, customers,"
                        + " grouping_changes, grouping_offers, grouping_campaigns,"
                        + " product_variants, products, organization_members,"
                        + " organizations, user_accounts, event_publication CASCADE");
        bearer = token(account("+221771234567"));
        organizationId = organization(bearer, "Boutique Awa");
    }

    @Test
    void publicationProvidesStableAnonymousLinkWithoutCreatingAGrouping() throws Exception {
        var product = product(organizationId, bearer, "Sac en cuir");
        var variantId = product.at("/variants/0/id").asText();

        var draftStore =
                body(
                        authorized(
                                        put(path())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "displayName",
                                                                        "Boutique Awa"))))
                                .andExpect(status().isOk())
                                .andReturn());
        String link = draftStore.path("publicIdentifier").asText();
        assertThat(link).isNotBlank();

        var draft =
                body(
                        authorized(
                                        post(path() + "/offers")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "variantId",
                                                                        variantId,
                                                                        "priceXof",
                                                                        8500))))
                                .andExpect(status().isOk())
                                .andReturn());
        String offerId = draft.at("/offers/0/id").asText();
        assertThat(draft.at("/offers/0/published").asBoolean()).isFalse();

        mvc.perform(get(publicPath(link))).andExpect(status().isNotFound());
        var published =
                body(
                        authorized(
                                        put(path() + "/offers/" + offerId)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "version",
                                                                        0,
                                                                        "priceXof",
                                                                        8500,
                                                                        "published",
                                                                        true))))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(published.at("/offers/0/version").asLong()).isEqualTo(1);

        mvc.perform(get(publicPath(link)))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Cache-Control",
                                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.offers[0].productName").value("Sac en cuir"))
                .andExpect(jsonPath("$.offers[0].priceXof").value(8500))
                .andExpect(jsonPath("$.offers[0].published").value(true));

        // Direct-cart quote uses only current published prices, no authentication, no order.
        mvc.perform(
                        post(publicPath(link) + "/quote")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "lines",
                                                        List.of(
                                                                Map.of(
                                                                        "offerId",
                                                                        offerId,
                                                                        "quantity",
                                                                        2))))))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Cache-Control",
                                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.currency").value("XOF"))
                .andExpect(jsonPath("$.goodsSubtotalXof").value(17000))
                .andExpect(jsonPath("$.lines[0].unitPriceXof").value(8500))
                .andExpect(jsonPath("$.lines[0].priceRevision").value(1))
                .andExpect(jsonPath("$.checkoutAvailable").value(false));
        mvc.perform(
                        post(publicPath(link) + "/quote")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "lines",
                                                        List.of(
                                                                Map.of(
                                                                        "offerId",
                                                                        offerId,
                                                                        "quantity",
                                                                        101))))))
                .andExpect(status().isBadRequest());

        // Published storefront does not create an implicit campaign or order.
        assertThat(jdbc.queryForObject("select count(*) from grouping_campaigns", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from orders", Long.class)).isZero();

        var renamed =
                body(
                        authorized(
                                        put(path())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "displayName",
                                                                        "Boutique renommée"))))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(renamed.path("publicIdentifier").asText()).isEqualTo(link);
        mvc.perform(get(publicPath(link)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Boutique renommée"));

        authorized(
                        put(path() + "/offers/" + offerId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        1,
                                                        "priceXof",
                                                        8500,
                                                        "published",
                                                        false))))
                .andExpect(status().isOk());
        mvc.perform(get(publicPath(link))).andExpect(status().isNotFound());
        mvc.perform(
                        post(publicPath(link) + "/quote")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "lines",
                                                        List.of(
                                                                Map.of(
                                                                        "offerId",
                                                                        offerId,
                                                                        "quantity",
                                                                        1))))))
                .andExpect(status().isConflict());
    }

    @Test
    void preventsCrossTenantOrAnonymousMutationsAndDuplicateOffers() throws Exception {
        var product = product(organizationId, bearer, "Sac");
        String variant = product.at("/variants/0/id").asText();

        // No merchant endpoint is anonymous.
        mvc.perform(
                        put(path())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("displayName", "Vol"))))
                .andExpect(status().isUnauthorized());
        var second = token(account("+221781234567"));
        UUID other = organization(second, "Autre boutique");
        mvc.perform(get(path()).header("Authorization", "Bearer " + second))
                .andExpect(status().isForbidden());

        authorized(
                        put(path())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("displayName", "Boutique Awa"))))
                .andExpect(status().isOk());
        authorized(
                        post(path() + "/offers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("variantId", variant, "priceXof", 1000))))
                .andExpect(status().isOk());
        authorized(
                        post(path() + "/offers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("variantId", variant, "priceXof", 1000))))
                .andExpect(status().isConflict());

        // Tenant B cannot publish tenant A's variant, even if the UUID is known.
        mvc.perform(
                        put("/api/organizations/" + other + "/direct-store")
                                .header("Authorization", "Bearer " + second)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("displayName", "Boutique B"))))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/organizations/" + other + "/direct-store/offers")
                                .header("Authorization", "Bearer " + second)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("variantId", variant, "priceXof", 1000))))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from direct_offers", Long.class))
                .isEqualTo(1);
    }

    @Test
    void rejectsPriceConflictAndArchivedProducts() throws Exception {
        var product = product(organizationId, bearer, "Robe");
        String variant = product.at("/variants/0/id").asText();
        authorized(
                        put(path())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("displayName", "Boutique Awa"))))
                .andExpect(status().isOk());
        var store =
                body(
                        authorized(
                                        post(path() + "/offers")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "variantId",
                                                                        variant,
                                                                        "priceXof",
                                                                        4500))))
                                .andExpect(status().isOk())
                                .andReturn());
        String offerId = store.at("/offers/0/id").asText();
        var id = store.path("publicIdentifier").asText();
        authorized(
                        put(path() + "/offers/" + offerId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        0,
                                                        "priceXof",
                                                        4500,
                                                        "published",
                                                        true))))
                .andExpect(status().isOk());
        authorized(
                        put(path() + "/offers/" + offerId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        0,
                                                        "priceXof",
                                                        3000,
                                                        "published",
                                                        true))))
                .andExpect(status().isConflict());
        authorized(
                        put(path() + "/offers/" + offerId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        1,
                                                        "priceXof",
                                                        0,
                                                        "published",
                                                        true))))
                .andExpect(status().isBadRequest());

        authorized(
                        post("/api/organizations/"
                                        + organizationId
                                        + "/products/"
                                        + product.path("id").asText()
                                        + "/archive")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        product.path("version").asLong()))))
                .andExpect(status().isOk());
        mvc.perform(get(publicPath(id))).andExpect(status().isNotFound());
        authorized(
                        put(path() + "/offers/" + offerId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        1,
                                                        "priceXof",
                                                        4500,
                                                        "published",
                                                        true))))
                .andExpect(status().isConflict());
    }

    @Test
    void directCheckoutCreatesIsolatedImmutableDraftAndReplaysWithoutPayment() throws Exception {
        var product = product(organizationId, bearer, "Panier direct");
        String variantId = product.at("/variants/0/id").asText();
        var store =
                body(
                        authorized(
                                        put(path())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "displayName",
                                                                        "Boutique Directe"))))
                                .andExpect(status().isOk())
                                .andReturn());
        String publicIdentifier = store.path("publicIdentifier").asText();
        var added =
                body(
                        authorized(
                                        post(path() + "/offers")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "variantId",
                                                                        variantId,
                                                                        "priceXof",
                                                                        8500))))
                                .andExpect(status().isOk())
                                .andReturn());
        String offerId = added.at("/offers/0/id").asText();
        authorized(
                        put(path() + "/offers/" + offerId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        0,
                                                        "priceXof",
                                                        8500,
                                                        "published",
                                                        true))))
                .andExpect(status().isOk());

        String key = "direct-create-test-0001";
        var command =
                Map.of(
                        "contact",
                                Map.of(
                                        "firstName", "Awa",
                                        "lastName", "Diop",
                                        "phone", "+221771234567"),
                        "fulfillmentMethod", "PICKUP",
                        "lines",
                                List.of(
                                        Map.of(
                                                "offerId", offerId,
                                                "quantity", 2,
                                                "expectedUnitPriceXof", 8500,
                                                "expectedPriceRevision", 1)));
        String endpoint = publicPath(publicIdentifier) + "/checkout";
        var created =
                body(
                        mvc.perform(
                                        post(endpoint)
                                                .header("Idempotency-Key", key)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(json.writeValueAsString(command)))
                                .andExpect(status().isCreated())
                                .andExpect(
                                        header().string(
                                                        "Cache-Control",
                                                        org.hamcrest.Matchers.containsString(
                                                                "no-store")))
                                .andExpect(jsonPath("$.goodsSubtotalXof").value(17000))
                                .andExpect(jsonPath("$.platformFeeXof").value(300))
                                .andExpect(jsonPath("$.amountBeforeShippingXof").value(17300))
                                .andExpect(jsonPath("$.paymentAvailable").value(false))
                                .andExpect(
                                        jsonPath("$.status").value("PENDING_PAYMENT_INTEGRATION"))
                                .andReturn());
        assertThat(created.path("orderNumber").asText()).startsWith("DIR-");

        // Merchant-only reads expose immutable snapshots, never another tenant's data.
        String merchantPath = "/api/organizations/" + organizationId + "/direct-orders";
        mvc.perform(get(merchantPath)).andExpect(status().isUnauthorized());
        authorized(get(merchantPath + "?page=-1")).andExpect(status().isBadRequest());
        var page =
                body(
                        authorized(get(merchantPath))
                                .andExpect(status().isOk())
                                .andExpect(
                                        header().string(
                                                        "Cache-Control",
                                                        org.hamcrest.Matchers.containsString(
                                                                "no-store")))
                                .andReturn());
        assertThat(page.path("total").asLong()).isEqualTo(1);
        assertThat(page.at("/items/0/orderNumber").asText())
                .isEqualTo(created.path("orderNumber").asText());
        assertThat(page.at("/items/0/itemCount").asInt()).isEqualTo(1);
        assertThat(page.toString()).doesNotContain("+221771234567");

        String orderId = page.at("/items/0/id").asText();
        var detail =
                body(
                        authorized(get(merchantPath + "/" + orderId))
                                .andExpect(status().isOk())
                                .andExpect(
                                        header().string(
                                                        "Cache-Control",
                                                        org.hamcrest.Matchers.containsString(
                                                                "no-store")))
                                .andReturn());
        assertThat(detail.at("/contact/firstName").asText()).isEqualTo("Awa");
        assertThat(detail.at("/contact/phoneE164").asText()).isEqualTo("+221771234567");
        assertThat(detail.at("/items/0/productName").asText()).isEqualTo("Panier direct");
        assertThat(detail.at("/items/0/quantity").asInt()).isEqualTo(2);
        assertThat(detail.at("/items/0/unitPriceXof").asLong()).isEqualTo(8500);
        assertThat(detail.at("/order/amountBeforeShippingXof").asLong()).isEqualTo(17300);
        assertThat(detail.at("/platformFeeRule/amount").asText()).isEqualTo("300");

        var secondBearer = token(account("+221781234567"));
        UUID secondOrg = organization(secondBearer, "Seconde boutique");
        mvc.perform(get(merchantPath).header("Authorization", "Bearer " + secondBearer))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get(merchantPath + "/" + orderId)
                                .header("Authorization", "Bearer " + secondBearer))
                .andExpect(status().isForbidden());
        var secondPage =
                body(
                        mvc.perform(
                                        get("/api/organizations/" + secondOrg + "/direct-orders")
                                                .header("Authorization", "Bearer " + secondBearer))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(secondPage.path("total").asLong()).isZero();
        mvc.perform(
                        get("/api/organizations/" + secondOrg + "/direct-orders/" + orderId)
                                .header("Authorization", "Bearer " + secondBearer))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select count(*) from direct_orders", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from direct_order_items", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from grouping_campaigns", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from orders", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment_requests", Long.class))
                .isZero();

        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(command)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value(created.path("orderNumber").asText()));
        assertThat(jdbc.queryForObject("select count(*) from direct_orders", Long.class))
                .isEqualTo(1);

        var changed =
                Map.of(
                        "contact", command.get("contact"),
                        "fulfillmentMethod", "PICKUP",
                        "lines",
                                List.of(
                                        Map.of(
                                                "offerId",
                                                offerId,
                                                "quantity",
                                                1,
                                                "expectedUnitPriceXof",
                                                8500,
                                                "expectedPriceRevision",
                                                1)));
        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(changed)))
                .andExpect(status().isConflict());

        authorized(
                        put(path() + "/offers/" + offerId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        1,
                                                        "priceXof",
                                                        9200,
                                                        "published",
                                                        true))))
                .andExpect(status().isOk());
        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", "direct-create-test-0002")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(command)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select unit_price_xof from direct_order_items", Long.class))
                .isEqualTo(8500L);
        assertThat(jdbc.queryForObject("select count(*) from direct_orders", Long.class))
                .isEqualTo(1);
    }

    @Test
    void directCheckoutValidatesDeliveryContactAndDoesNotUseGroupingOrders() throws Exception {
        var product = product(organizationId, bearer, "Robe directe");
        String variantId = product.at("/variants/0/id").asText();
        var store =
                body(
                        authorized(
                                        put(path())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of("displayName", "Atelier"))))
                                .andExpect(status().isOk())
                                .andReturn());
        var offers =
                body(
                        authorized(
                                        post(path() + "/offers")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "variantId",
                                                                        variantId,
                                                                        "priceXof",
                                                                        2000))))
                                .andExpect(status().isOk())
                                .andReturn());
        String offerId = offers.at("/offers/0/id").asText();
        authorized(
                        put(path() + "/offers/" + offerId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        0,
                                                        "priceXof",
                                                        2000,
                                                        "published",
                                                        true))))
                .andExpect(status().isOk());

        String endpoint = publicPath(store.path("publicIdentifier").asText()) + "/checkout";
        var lines =
                List.of(
                        Map.of(
                                "offerId",
                                offerId,
                                "quantity",
                                1,
                                "expectedUnitPriceXof",
                                2000,
                                "expectedPriceRevision",
                                1));
        var contact = Map.of("firstName", "Awa", "lastName", "Ndiaye", "phone", "+221771234567");
        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", "direct-invalid-address-0001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "contact",
                                                        contact,
                                                        "fulfillmentMethod",
                                                        "DELIVERY",
                                                        "lines",
                                                        lines))))
                .andExpect(status().isBadRequest());
        String policyPath = path() + "/delivery-policy";
        var defaultPolicy =
                body(authorized(get(policyPath)).andExpect(status().isOk()).andReturn());
        assertThat(defaultPolicy.path("mode").asText()).isEqualTo("DISABLED");
        assertThat(defaultPolicy.path("version").asLong()).isZero();
        var limitedPolicy =
                body(
                        authorized(
                                        put(policyPath)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "mode",
                                                                        "SELECTED",
                                                                        "allowedRegions",
                                                                        List.of("Dakar"),
                                                                        "version",
                                                                        0))))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(limitedPolicy.path("version").asLong()).isEqualTo(1);
        assertThat(limitedPolicy.at("/allowedRegions/0").asText()).isEqualTo("Dakar");
        authorized(
                        put(policyPath)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "mode",
                                                        "ALL",
                                                        "allowedRegions",
                                                        List.of(),
                                                        "version",
                                                        0))))
                .andExpect(status().isConflict());
        authorized(
                        put(policyPath)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "mode",
                                                        "SELECTED",
                                                        "allowedRegions",
                                                        List.of("Inconnue"),
                                                        "version",
                                                        1))))
                .andExpect(status().isBadRequest());
        mvc.perform(get(publicPath(store.path("publicIdentifier").asText()) + "/delivery-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SELECTED"));
        mvc.perform(
                        post(publicPath(store.path("publicIdentifier").asText())
                                        + "/reverse-geocode")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("latitude", 14.72, "longitude", -17.46))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"));
        mvc.perform(
                        post(publicPath(store.path("publicIdentifier").asText())
                                        + "/reverse-geocode")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("latitude", 92, "longitude", -17.46))))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post(publicPath(store.path("publicIdentifier").asText())
                                        + "/reverse-geocode")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());

        String coveragePath =
                publicPath(store.path("publicIdentifier").asText()) + "/delivery-coverage";
        mvc.perform(
                        post(coveragePath)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "latitude",
                                                        14.6937,
                                                        "longitude",
                                                        -17.4474))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSIDE_ZONE"))
                .andExpect(jsonPath("$.region").value("Dakar"))
                .andExpect(jsonPath("$.preliminaryAllowed").value(true));
        mvc.perform(
                        post(coveragePath)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("latitude", 14.791, "longitude", -16.928))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUTSIDE_ZONE"))
                .andExpect(jsonPath("$.region").value("Thiès"))
                .andExpect(jsonPath("$.preliminaryAllowed").value(false));
        mvc.perform(
                        post(coveragePath)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("latitude", 5.3599, "longitude", -4.008))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUTSIDE_SENEGAL"))
                .andExpect(jsonPath("$.preliminaryAllowed").value(false));
        mvc.perform(post(coveragePath).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        var outside =
                Map.of(
                        "contact",
                        Map.of(
                                "firstName",
                                "Awa",
                                "lastName",
                                "Ndiaye",
                                "phone",
                                "+221771234567",
                                "deliveryAddress",
                                Map.of(
                                        "country",
                                        "SN",
                                        "region",
                                        "Thiès",
                                        "city",
                                        "Thiès",
                                        "commune",
                                        "Thiès",
                                        "details",
                                        "Près du marché")),
                        "fulfillmentMethod",
                        "DELIVERY",
                        "lines",
                        lines);
        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", "direct-outside-region-0001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(outside)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from direct_orders", Long.class)).isZero();

        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", "direct-valid-address-0001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "contact",
                                                        Map.of(
                                                                "firstName", "Awa",
                                                                "lastName", "Ndiaye",
                                                                "phone", "+221771234567",
                                                                "deliveryAddress",
                                                                        Map.of(
                                                                                "country",
                                                                                "SN",
                                                                                "region",
                                                                                "Dakar",
                                                                                "city",
                                                                                "Dakar",
                                                                                "commune",
                                                                                "Yoff",
                                                                                "details",
                                                                                "Près du marché")),
                                                        "fulfillmentMethod",
                                                        "DELIVERY",
                                                        "lines",
                                                        lines))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shippingQuoted").value(false))
                .andExpect(jsonPath("$.paymentAvailable").value(false));
        assertThat(jdbc.queryForObject("select count(*) from direct_orders", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from orders", Long.class)).isZero();
        authorized(
                        put(policyPath)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "mode",
                                                        "DISABLED",
                                                        "allowedRegions",
                                                        List.of(),
                                                        "version",
                                                        1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", "direct-disabled-region-0001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(outside)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from direct_orders", Long.class))
                .isEqualTo(1);
        mvc.perform(get(policyPath)).andExpect(status().isUnauthorized());
        var second = token(account("+221781234567"));
        UUID otherOrg = organization(second, "Autre boutique");
        mvc.perform(get(policyPath).header("Authorization", "Bearer " + second))
                .andExpect(status().isForbidden());
        mvc.perform(
                        put(policyPath)
                                .header("Authorization", "Bearer " + second)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "mode",
                                                        "ALL",
                                                        "allowedRegions",
                                                        List.of(),
                                                        "version",
                                                        2))))
                .andExpect(status().isForbidden());
        assertThat(otherOrg).isNotEqualTo(organizationId);
    }

    private JsonNode product(UUID organization, String authorization, String name)
            throws Exception {
        return body(
                mvc.perform(
                                post("/api/organizations/" + organization + "/products")
                                        .header("Authorization", "Bearer " + authorization)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(Map.of("name", name))))
                        .andExpect(status().isCreated())
                        .andReturn());
    }

    private String path() {
        return "/api/organizations/" + organizationId + "/direct-store";
    }

    private String publicPath(String id) {
        return "/api/public/direct-stores/" + id;
    }

    private ResultActions authorized(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + bearer));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private UUID account(String phone) {
        var id = UUID.randomUUID();
        jdbc.update(
                "insert into user_accounts(id,phone_number,password_hash,status,created_at,updated_at)"
                        + " values (?,?,?,'ACTIVE',current_timestamp,current_timestamp)",
                id,
                phone,
                passwords.encode("Long fixture password 2026"));
        return id;
    }

    private String token(UUID userId) {
        var claims =
                JwtClaimsSet.builder()
                        .claim("sid", UUID.randomUUID().toString())
                        .issuer("https://groupia.test")
                        .audience(List.of("groupia-api-test"))
                        .subject(userId.toString())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(600))
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private UUID organization(String jwt, String name) throws Exception {
        return UUID.fromString(
                body(mvc.perform(
                                        post("/api/organizations")
                                                .header("Authorization", "Bearer " + jwt)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of("name", name))))
                                .andExpect(status().isCreated())
                                .andReturn())
                        .get("id")
                        .asText());
    }
}
