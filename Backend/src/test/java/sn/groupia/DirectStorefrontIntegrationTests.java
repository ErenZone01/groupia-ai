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

@SpringBootTest(
        properties = {
            "groupia.direct-sales.checkout.enabled=true",
            "groupia.direct-sales.payment-obligation.auto-prepare.enabled=true"
        })
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
                .andExpect(jsonPath("$.checkoutAvailable").value(false))
                .andExpect(jsonPath("$.draftCreationAvailable").value(true));
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
        assertThat(
                        detail.at("/order/acceptedTotalXof").isNull()
                                || detail.at("/order/acceptedTotalXof").isMissingNode())
                .isTrue();
        assertThat(detail.at("/platformFeeRule/amount").asText()).isEqualTo("300");
        mvc.perform(get(merchantPath + "/" + orderId + "/payment-status"))
                .andExpect(status().isUnauthorized());
        authorized(get(merchantPath + "/" + orderId + "/payment-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financialStatus").value("NOT_PREPARED"))
                .andExpect(jsonPath("$.fulfillmentAllowed").value(false));

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

        // Shipping previews take offerId and quantity only. Checkout expected-price
        // fields must not be included in this distinct, strictly typed API contract.
        var shippingLines = List.of(Map.of("offerId", offerId, "quantity", 1));
        String shippingPath = path() + "/shipping-fees";
        String publicShippingPath =
                publicPath(store.path("publicIdentifier").asText()) + "/shipping-quote";
        var emptyShipping =
                body(authorized(get(shippingPath)).andExpect(status().isOk()).andReturn());
        assertThat(emptyShipping.path("version").asLong()).isZero();
        assertThat(emptyShipping.path("regionFeesXof").size()).isZero();
        var manualShippingRequest =
                Map.of("fulfillmentMethod", "DELIVERY", "lines", shippingLines, "region", "Dakar");
        mvc.perform(
                        post(publicShippingPath)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(manualShippingRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FEE_UNCONFIGURED"))
                .andExpect(jsonPath("$.shippingQuoted").value(false))
                .andExpect(jsonPath("$.shippingFeeXof").doesNotExist())
                .andExpect(jsonPath("$.paymentAvailable").value(false));
        mvc.perform(
                        post(publicShippingPath)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "fulfillmentMethod",
                                                        "PICKUP",
                                                        "lines",
                                                        shippingLines))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUOTED"))
                .andExpect(jsonPath("$.shippingFeeXof").value(0))
                .andExpect(jsonPath("$.shippingQuoted").value(true));
        var shippingRates =
                Map.of("regionFeesXof", Map.of("Dakar", 1500, "Thiès", 0), "version", 0);
        authorized(
                        put(shippingPath)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(shippingRates)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.regionFeesXof.Dakar").value(1500));
        authorized(
                        put(shippingPath)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(shippingRates)))
                .andExpect(status().isConflict());
        authorized(
                        put(shippingPath)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "regionFeesXof",
                                                        Map.of("Dakar", -1),
                                                        "version",
                                                        1))))
                .andExpect(status().isBadRequest());
        authorized(
                        put(shippingPath)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "regionFeesXof",
                                                        Map.of("Fausse région", 1000),
                                                        "version",
                                                        1))))
                .andExpect(status().isBadRequest());

        var manualShipping =
                body(
                        mvc.perform(
                                        post(publicShippingPath)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                manualShippingRequest)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("QUOTED"))
                                .andExpect(jsonPath("$.shippingFeeXof").value(1500))
                                .andExpect(
                                        jsonPath("$.locationVerification")
                                                .value("MANUAL_UNVERIFIED"))
                                .andExpect(jsonPath("$.paymentAvailable").value(false))
                                .andReturn());
        assertThat(manualShipping.path("totalXof").asLong())
                .isEqualTo(
                        manualShipping.path("goodsSubtotalXof").asLong()
                                + manualShipping.path("platformFeeXof").asLong()
                                + 1500L);
        mvc.perform(
                        post(publicShippingPath)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "fulfillmentMethod",
                                                        "DELIVERY",
                                                        "lines",
                                                        shippingLines,
                                                        "deliveryPoint",
                                                        Map.of(
                                                                "latitude",
                                                                14.6937,
                                                                "longitude",
                                                                -17.4474)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUOTED"))
                .andExpect(jsonPath("$.region").value("Dakar"))
                .andExpect(jsonPath("$.locationVerification").value("MAP_REGION_MATCHED"))
                .andExpect(jsonPath("$.shippingFeeXof").value(1500));
        mvc.perform(
                        post(publicShippingPath)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "fulfillmentMethod",
                                                        "DELIVERY",
                                                        "lines",
                                                        shippingLines,
                                                        "region",
                                                        "Thiès"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUTSIDE_ZONE"))
                .andExpect(jsonPath("$.shippingQuoted").value(false));
        mvc.perform(
                        post(publicShippingPath)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "fulfillmentMethod",
                                                        "DELIVERY",
                                                        "lines",
                                                        shippingLines,
                                                        "deliveryPoint",
                                                        Map.of(
                                                                "latitude",
                                                                5.3599,
                                                                "longitude",
                                                                -4.008)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUTSIDE_SENEGAL"))
                .andExpect(jsonPath("$.shippingQuoted").value(false));
        mvc.perform(
                        post(publicShippingPath)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "fulfillmentMethod",
                                                        "DELIVERY",
                                                        "lines",
                                                        shippingLines,
                                                        "region",
                                                        "Inconnue"))))
                .andExpect(status().isBadRequest());
        mvc.perform(get(shippingPath)).andExpect(status().isUnauthorized());

        // Synthetic identifiers split to avoid false positives in the secret scanner.
        String gpsKeyPrefix = "direct-gps-";
        String gpsDakarKey = gpsKeyPrefix + "dakar-0001";
        var gpsOutside =
                Map.of(
                        "contact",
                        contact,
                        "fulfillmentMethod",
                        "DELIVERY",
                        "deliveryPoint",
                        Map.of("latitude", 14.791, "longitude", -16.928),
                        "lines",
                        lines);
        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", gpsKeyPrefix + "thies-0001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(gpsOutside)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIRECT_DELIVERY_OUTSIDE_ZONE"));
        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", gpsKeyPrefix + "abidjan-0001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "contact",
                                                        contact,
                                                        "fulfillmentMethod",
                                                        "DELIVERY",
                                                        "deliveryPoint",
                                                        Map.of(
                                                                "latitude",
                                                                5.3599,
                                                                "longitude",
                                                                -4.008),
                                                        "lines",
                                                        lines))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIRECT_DELIVERY_LOCATION_UNVERIFIED"));
        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", gpsKeyPrefix + "invalid-0001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "contact",
                                                        contact,
                                                        "fulfillmentMethod",
                                                        "DELIVERY",
                                                        "deliveryPoint",
                                                        Map.of(
                                                                "latitude",
                                                                100.0,
                                                                "longitude",
                                                                -17.45),
                                                        "lines",
                                                        lines))))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", gpsKeyPrefix + "partial-0001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "contact",
                                                        contact,
                                                        "fulfillmentMethod",
                                                        "DELIVERY",
                                                        "deliveryPoint",
                                                        Map.of("latitude", 14.72),
                                                        "lines",
                                                        lines))))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from direct_orders", Long.class)).isZero();

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
        var manualSnapshot =
                json.readTree(
                        jdbc.queryForObject(
                                "select delivery_address_snapshot::text from direct_orders"
                                        + " where checkout_idempotency_key=?",
                                String.class,
                                "direct-valid-address-0001"));
        assertThat(manualSnapshot.path("coverageStatus").asText()).isEqualTo("UNVERIFIED_MANUAL");
        assertThat(jdbc.queryForObject("select count(*) from orders", Long.class)).isZero();
        var gpsInside =
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
                                        "details",
                                        "Derrière la pharmacie")),
                        "fulfillmentMethod",
                        "DELIVERY",
                        "deliveryPoint",
                        Map.of("latitude", 14.6937, "longitude", -17.4474),
                        "lines",
                        lines);
        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", gpsDakarKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(gpsInside)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentAvailable").value(false))
                .andExpect(jsonPath("$.shippingQuoted").value(false));
        var savedGps =
                json.readTree(
                        jdbc.queryForObject(
                                "select delivery_address_snapshot::text from direct_orders"
                                        + " where checkout_idempotency_key=?",
                                String.class,
                                gpsDakarKey));
        assertThat(savedGps.path("region").asText()).isEqualTo("Dakar");
        assertThat(savedGps.path("locationMethod").asText()).isEqualTo("MAP");
        assertThat(savedGps.path("coverageStatus").asText()).isEqualTo("REGION_MATCHED");
        assertThat(savedGps.path("regionSource").asText()).contains("geoBoundaries");
        assertThat(savedGps.path("latitude").asDouble()).isEqualTo(14.6937);
        assertThat(savedGps.path("details").asText()).isEqualTo("Derrière la pharmacie");
        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", gpsDakarKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(gpsInside)))
                .andExpect(status().isOk());
        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", gpsDakarKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "contact",
                                                        contact,
                                                        "fulfillmentMethod",
                                                        "DELIVERY",
                                                        "deliveryPoint",
                                                        Map.of(
                                                                "latitude",
                                                                14.6937,
                                                                "longitude",
                                                                -17.4474),
                                                        "lines",
                                                        lines))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("select count(*) from direct_orders", Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from orders", Long.class)).isZero();
        // The total acknowledged by the buyer must match a fresh quote obtained
        // under the same storefront lock as rate modifications.
        String acceptedPrefix = "accepted-" + "direct-";
        String acceptedGpsKey = acceptedPrefix + "dakar-0001";
        long acceptedPrice = manualShipping.path("totalXof").asLong();
        var acceptedGps = new java.util.HashMap<String, Object>(gpsInside);
        acceptedGps.put("acceptedTotalXof", acceptedPrice);
        byte[] recoveryBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(recoveryBytes);
        String recoveryToken =
                Base64.getUrlEncoder().withoutPadding().encodeToString(recoveryBytes);
        acceptedGps.put("recoveryToken", recoveryToken);
        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", acceptedPrefix + "wrong-0001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "contact",
                                                        gpsInside.get("contact"),
                                                        "fulfillmentMethod",
                                                        "DELIVERY",
                                                        "deliveryPoint",
                                                        gpsInside.get("deliveryPoint"),
                                                        "lines",
                                                        lines,
                                                        "acceptedTotalXof",
                                                        acceptedPrice - 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIRECT_TOTAL_CHANGED"));
        assertThat(jdbc.queryForObject("select count(*) from direct_orders", Long.class))
                .isEqualTo(2);
        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", acceptedGpsKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(acceptedGps)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shippingFeeXof").value(1500))
                .andExpect(jsonPath("$.acceptedTotalXof").value(acceptedPrice))
                .andExpect(jsonPath("$.shippingRateVersion").value(1))
                .andExpect(jsonPath("$.shippingQuoted").value(true))
                .andExpect(jsonPath("$.paymentAvailable").value(false));
        var savedAccepted =
                jdbc.queryForMap(
                        "select shipping_fee_xof,accepted_total_xof,shipping_rate_version"
                                + " from direct_orders where checkout_idempotency_key=?",
                        acceptedGpsKey);
        assertThat(((Number) savedAccepted.get("shipping_fee_xof")).longValue()).isEqualTo(1500L);
        assertThat(((Number) savedAccepted.get("accepted_total_xof")).longValue())
                .isEqualTo(acceptedPrice);
        assertThat(((Number) savedAccepted.get("shipping_rate_version")).longValue()).isEqualTo(1L);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payment_requests"
                                        + " where organization_id=? and purpose='DIRECT_ORDER_INITIAL'",
                                Long.class,
                                organizationId))
                .isEqualTo(1L);

        UUID acceptedOrderId =
                jdbc.queryForObject(
                        "select id from direct_orders where checkout_idempotency_key=?",
                        UUID.class,
                        acceptedGpsKey);
        var preparedObligation =
                jdbc.queryForMap(
                        "select direct_order_id,target_type,target_id,expected_amount,"
                                + "goods_amount,fee_amount,shipping_amount,acceptance_status"
                                + " from payment_requests where organization_id=?"
                                + " and direct_order_id=?",
                        organizationId,
                        acceptedOrderId);
        assertThat(preparedObligation.get("direct_order_id")).isEqualTo(acceptedOrderId);
        assertThat(preparedObligation.get("target_type")).isEqualTo("DIRECT_ORDER");
        assertThat(preparedObligation.get("target_id")).isEqualTo(acceptedOrderId);
        assertThat(((Number) preparedObligation.get("expected_amount")).longValue())
                .isEqualTo(acceptedPrice);
        assertThat(((Number) preparedObligation.get("shipping_amount")).longValue())
                .isEqualTo(1500L);
        assertThat(preparedObligation.get("acceptance_status")).isEqualTo("OPEN");

        String merchantOrdersPath = "/api/organizations/" + organizationId + "/direct-orders";
        authorized(get(merchantOrdersPath + "/" + acceptedOrderId + "/payment-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financialStatus").value("PREPARED"))
                .andExpect(jsonPath("$.acceptedTotalXof").value(acceptedPrice))
                .andExpect(jsonPath("$.fulfillmentAllowed").value(false));
        var acceptedMerchantDetail =
                body(
                        authorized(get(merchantOrdersPath + "/" + acceptedOrderId))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(acceptedMerchantDetail.at("/order/shippingFeeXof").asLong()).isEqualTo(1500L);
        assertThat(acceptedMerchantDetail.at("/order/acceptedTotalXof").asLong())
                .isEqualTo(acceptedPrice);
        assertThat(acceptedMerchantDetail.at("/order/shippingRateVersion").asLong()).isEqualTo(1L);
        assertThat(acceptedMerchantDetail.at("/deliveryAddress/coverageStatus").asText())
                .isEqualTo("REGION_MATCHED");
        var acceptedMerchantList =
                body(authorized(get(merchantOrdersPath)).andExpect(status().isOk()).andReturn());
        assertThat(acceptedMerchantList.path("items").toString())
                .contains("acceptedTotalXof")
                .contains(Long.toString(acceptedPrice));
        String tokenHash =
                jdbc.queryForObject(
                        "select recovery_token_hash from direct_orders"
                                + " where checkout_idempotency_key=?",
                        String.class,
                        acceptedGpsKey);
        assertThat(tokenHash).matches("[0-9a-f]{64}").isNotEqualTo(recoveryToken);
        var tracked =
                body(
                        mvc.perform(
                                        post("/api/public/direct-orders/lookup")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of("token", recoveryToken))))
                                .andExpect(status().isOk())
                                .andExpect(header().string("Cache-Control", "no-store"))
                                .andExpect(jsonPath("$.paymentAvailable").value(false))
                                .andExpect(jsonPath("$.acceptedTotalXof").value(acceptedPrice))
                                .andExpect(jsonPath("$.orderNumber").exists())
                                .andReturn());
        assertThat(tracked.has("phoneE164")).isFalse();
        assertThat(tracked.has("customerContact")).isFalse();
        assertThat(tracked.has("deliveryAddress")).isFalse();

        var paymentStatus =
                body(
                        mvc.perform(
                                        post("/api/public/direct-orders/payment-status")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of("token", recoveryToken))))
                                .andExpect(status().isOk())
                                .andExpect(header().string("Cache-Control", "no-store"))
                                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                                .andExpect(
                                        jsonPath("$.orderNumber")
                                                .value(tracked.path("orderNumber").asText()))
                                .andExpect(jsonPath("$.currency").value("XOF"))
                                .andExpect(jsonPath("$.acceptedTotalXof").value(acceptedPrice))
                                .andExpect(jsonPath("$.preparationStatus").value("PREPARED"))
                                .andExpect(jsonPath("$.paymentAvailable").value(false))
                                .andReturn());
        assertThat(paymentStatus.has("organizationId")).isFalse();
        assertThat(paymentStatus.has("directOrderId")).isFalse();
        assertThat(paymentStatus.has("paymentRequestId")).isFalse();
        assertThat(paymentStatus.has("phoneE164")).isFalse();
        assertThat(paymentStatus.has("providerTransactionId")).isFalse();
        mvc.perform(
                        post("/api/public/direct-orders/payment-status")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("token", "DIR-000000000001"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DIRECT_ORDER_NOT_FOUND"));
        mvc.perform(
                        post("/api/public/direct-orders/lookup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "token",
                                                        Base64.getUrlEncoder()
                                                                .withoutPadding()
                                                                .encodeToString(new byte[32])))))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/public/direct-orders/lookup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of("token", "DIR-000000000001"))))
                .andExpect(status().isNotFound());
        var conflictingRecovery = new java.util.HashMap<String, Object>(acceptedGps);
        byte[] anotherBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(anotherBytes);
        conflictingRecovery.put(
                "recoveryToken",
                Base64.getUrlEncoder().withoutPadding().encodeToString(anotherBytes));
        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", acceptedGpsKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(conflictingRecovery)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        authorized(
                        put(shippingPath)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "regionFeesXof",
                                                        Map.of("Dakar", 1800),
                                                        "version",
                                                        1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // Exact replay is stable even after the merchant changes shipping prices.
        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", acceptedGpsKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(acceptedGps)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shippingFeeXof").value(1500))
                .andExpect(jsonPath("$.acceptedTotalXof").value(acceptedPrice));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payment_requests"
                                        + " where direct_order_id=?",
                                Long.class,
                                acceptedOrderId))
                .isEqualTo(1L);
        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", acceptedGpsKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "contact",
                                                        gpsInside.get("contact"),
                                                        "fulfillmentMethod",
                                                        "DELIVERY",
                                                        "deliveryPoint",
                                                        gpsInside.get("deliveryPoint"),
                                                        "lines",
                                                        lines,
                                                        "acceptedTotalXof",
                                                        acceptedPrice + 300))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", acceptedPrefix + "stale-0001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(acceptedGps)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIRECT_TOTAL_CHANGED"));
        var updatedGps = new java.util.HashMap<String, Object>(gpsInside);
        updatedGps.put("acceptedTotalXof", acceptedPrice + 300);
        mvc.perform(
                        post(endpoint)
                                .header("Idempotency-Key", acceptedPrefix + "updated-0001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(updatedGps)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shippingFeeXof").value(1800))
                .andExpect(jsonPath("$.shippingRateVersion").value(2))
                .andExpect(jsonPath("$.paymentAvailable").value(false));
        assertThat(jdbc.queryForObject("select count(*) from direct_orders", Long.class))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from payment_requests", Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from payments", Long.class)).isZero();
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
                .isEqualTo(4);
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
